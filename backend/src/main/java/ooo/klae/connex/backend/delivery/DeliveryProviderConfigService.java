package ooo.klae.connex.backend.delivery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.beans.DeliveryProviderConfig;
import ooo.klae.connex.backend.delivery.provider.esp.HttpEspDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.sms.SmsHttpDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.smtp.SmtpDeliveryProvider;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigDto;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigRequest;
import ooo.klae.connex.backend.dto.DeliveryWebhookTokenDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mappers.DeliveryProviderConfigMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Resolves the effective delivery provider for a workspace and channel, fail-closed, and manages the
 * per-workspace {@code delivery_provider_config} rows behind it. When an enabled receipt-capable ESP
 * config exists for the channel it overrides the built-in SMTP transport; otherwise resolution falls
 * back to the workspace mail transport unchanged, so the Slice-1 dispatch path is untouched when no
 * ESP is configured. Send credentials and webhook signing secrets are held only as opaque secret
 * references; the webhook URL token is stored only as its SHA-256.
 */
@Service
@RequiredArgsConstructor
public class DeliveryProviderConfigService implements DeliveryProviderReadiness {

    private static final String CREDENTIAL_KEY_API = "apiKey";
    private static final String CREDENTIAL_KEY_WEBHOOK_SECRET = "webhookSecret";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_ENDPOINT_LENGTH = 2048;
    private static final Pattern API_KEY = Pattern.compile("^[\\x21-\\x7e]{1,512}$");
    private static final Pattern SMS_SENDER_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 +._-]{0,31}$");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private final MailConfigResolver mailConfigResolver;
    private final DeliveryProviderConfigMapper deliveryProviderConfigMapper;
    private final DeliveryProviderSecretCipher deliveryProviderSecretCipher;
    private final AiEndpointAddressValidator aiEndpointAddressValidator;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Resolves the provider a workspace should use for a channel.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @return the resolved provider
     * @throws DeliveryProviderException when the channel is unsupported or no transport is configured
     */
    public ResolvedDeliveryProvider resolveForWorkspace(int workspaceId, DeliveryChannel channel) {
        if (channel == DeliveryChannel.SMS) {
            return resolveSms(deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token()));
        }
        requireEmail(channel);
        DeliveryProviderConfig config =
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
        if (isEnabledEsp(config)) {
            return resolveEsp(config);
        }
        ResolvedMailConfig mail = mailConfigResolver.resolveForWorkspace(workspaceId);
        if (mail == null || !mail.usable()) {
            throw new DeliveryProviderException("No usable mail transport is configured for delivery");
        }
        return ResolvedDeliveryProvider.of(
                SmtpDeliveryProvider.PROVIDER_ID,
                DeliveryChannel.EMAIL,
                workspaceId,
                DeliveryCredentials.none(),
                DeliveryTargetFingerprint.create(
                        SmtpDeliveryProvider.PROVIDER_ID,
                        mail.configurationVersion(),
                        smtpEndpointIdentity(mail),
                        mail.credentialReference()),
                mail);
    }

    @Override
    public boolean isReady(int workspaceId, DeliveryChannel channel) {
        if (channel == DeliveryChannel.SMS) {
            DeliveryProviderConfig smsConfig =
                    deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
            return isEnabledSms(smsConfig)
                    && !isBlank(smsConfig.getEndpoint())
                    && !isBlank(smsConfig.getCredentialRef())
                    && deliveryProviderSecretCipher.isAvailable();
        }
        if (channel != DeliveryChannel.EMAIL) {
            return false;
        }
        DeliveryProviderConfig config =
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
        if (isEnabledEsp(config)) {
            return !isBlank(config.getEndpoint())
                    && !isBlank(config.getCredentialRef())
                    && deliveryProviderSecretCipher.isAvailable();
        }
        ResolvedMailConfig mail = mailConfigResolver.resolveForWorkspace(workspaceId);
        return mail != null && mail.usable();
    }

    /**
     * Resolves a workspace and provider from the opaque webhook token in a request URL, hashing the
     * token and looking it up on the unique hash. The workspace is taken from the matched row and never
     * from the request body. The returned provider carries the decrypted webhook signing secret.
     * @param rawToken the raw webhook URL token
     * @return the resolved provider carrying the webhook secret
     * @throws DeliveryProviderException when no enabled, webhook-capable config matches the token
     */
    public ResolvedDeliveryProvider resolveByWebhookToken(String rawToken) {
        if (isBlank(rawToken)) {
            throw new DeliveryProviderException("Webhook token is required");
        }
        DeliveryProviderConfig config =
                deliveryProviderConfigMapper.findByWebhookTokenHash(sha256Hex(rawToken.trim()));
        if (config == null || !config.isEnabled() || isBlank(config.getWebhookSecretRef())) {
            throw new DeliveryProviderException("Webhook token is not valid");
        }
        String secret = deliveryProviderSecretCipher.decryptWebhookSecret(
                config.getWorkspaceId(), config.getWebhookSecretRef());
        if (isBlank(secret)) {
            throw new DeliveryProviderException("Webhook token is not valid");
        }
        return new ResolvedDeliveryProvider(
                config.getProvider(),
                DeliveryChannel.fromToken(config.getChannel()),
                config.getWorkspaceId(),
                config.getEndpoint(),
                config.getFromAddress(),
                config.getFromName(),
                DeliveryCredentials.of(Map.of(CREDENTIAL_KEY_WEBHOOK_SECRET, secret)));
    }

    /**
     * Lists the active workspace's delivery provider settings, masked.
     * @return the masked provider settings, one per configured channel
     */
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public List<DeliveryProviderConfigDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return deliveryProviderConfigMapper.listByWorkspace(workspaceId).stream()
                .map(DeliveryProviderConfigDto::from)
                .toList();
    }

    /**
     * Creates or updates the active workspace's provider settings for one channel. The send credential
     * is write-only: a blank credential preserves the stored one when the provider is unchanged.
     * @param request the submitted settings
     * @return the saved masked settings
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public DeliveryProviderConfigDto save(DeliveryProviderConfigRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (request == null) {
            throw new BadRequestException("Delivery provider configuration is required");
        }
        DeliveryChannel channel = resolveChannel(request.getChannel());
        String provider = resolveProvider(request.getProvider());
        requireProviderMatchesChannel(channel, provider);

        DeliveryProviderConfig existing =
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
        boolean sameProvider = existing != null && provider.equals(existing.getProvider());

        DeliveryProviderConfig config = new DeliveryProviderConfig();
        config.setWorkspaceId(workspaceId);
        config.setChannel(channel.token());
        config.setProvider(provider);
        config.setCreatedById(existing != null ? existing.getCreatedById() : actorId);
        config.setCredentialRef(sameProvider ? existing.getCredentialRef() : null);
        config.setCredentialLast4(sameProvider ? existing.getCredentialLast4() : null);
        config.setWebhookTokenHash(sameProvider ? existing.getWebhookTokenHash() : null);
        config.setWebhookSecretRef(sameProvider ? existing.getWebhookSecretRef() : null);

        String newCredential = switch (provider) {
            case SmtpDeliveryProvider.PROVIDER_ID -> validateAndPopulateSmtp(request, config);
            case HttpEspDeliveryProvider.PROVIDER_ID -> validateAndPopulateHttpEsp(request, config);
            case SmsHttpDeliveryProvider.PROVIDER_ID -> validateAndPopulateSmsHttp(request, config);
            default -> throw new BadRequestException("Unsupported delivery provider");
        };

        if (sameProvider && !isBlank(existing.getCredentialRef()) && newCredential == null
                && endpointHostChanged(existing.getEndpoint(), config.getEndpoint())) {
            throw new BadRequestException("Re-enter the credential to change the endpoint");
        }
        boolean hasCredential = newCredential != null || !isBlank(config.getCredentialRef());
        if (request.isEnabled() && requiresCredential(provider) && !hasCredential) {
            throw new BadRequestException("A stored API credential is required before enabling this provider");
        }
        if (newCredential != null) {
            config.setCredentialRef(deliveryProviderSecretCipher.encryptCredential(workspaceId, newCredential));
        }
        config.setEnabled(request.isEnabled());
        config.setIdempotentSubmission(
                !SmtpDeliveryProvider.PROVIDER_ID.equals(provider)
                        && request.isIdempotentSubmission());

        deliveryProviderConfigMapper.upsert(config);
        deleteReplacedCredential(workspaceId, existing, config, sameProvider);
        auditService.record("workspace.delivery_provider.save", "workspace", workspaceId, provider,
                "Updated delivery provider settings", null);
        return DeliveryProviderConfigDto.from(
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token()));
    }

    /**
     * Issues or rotates the webhook credential pair for the active workspace's ESP config on a channel.
     * The raw token and signing secret are returned exactly once; only the token's SHA-256 and the
     * secret's opaque reference are persisted.
     * @param channelToken the channel token
     * @return the one-time webhook credential reveal
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public DeliveryWebhookTokenDto issueWebhookToken(String channelToken) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        DeliveryChannel channel = resolveChannel(channelToken);
        DeliveryProviderConfig config =
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
        if (config == null || !HttpEspDeliveryProvider.PROVIDER_ID.equals(config.getProvider())) {
            throw new BadRequestException("A receipt-capable provider must be configured for this channel first");
        }
        String previousSecretRef = config.getWebhookSecretRef();
        String rawToken = randomHex();
        String rawSecret = randomHex();
        config.setWebhookTokenHash(sha256Hex(rawToken));
        config.setWebhookSecretRef(deliveryProviderSecretCipher.encryptWebhookSecret(workspaceId, rawSecret));
        deliveryProviderConfigMapper.upsert(config);
        if (!isBlank(previousSecretRef) && !previousSecretRef.equals(config.getWebhookSecretRef())) {
            deliveryProviderSecretCipher.deleteWebhookSecretReference(workspaceId, previousSecretRef);
        }
        auditService.record("workspace.delivery_provider.webhook_token", "workspace", workspaceId,
                config.getProvider(), "Issued a delivery webhook token", null);
        return new DeliveryWebhookTokenDto(rawToken, rawSecret, HttpEspDeliveryProvider.SIGNATURE_HEADER);
    }

    /**
     * Removes the active workspace's provider settings for a channel and deletes its stored secrets.
     * @param channelToken the channel token
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void delete(String channelToken) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        DeliveryChannel channel = resolveChannel(channelToken);
        DeliveryProviderConfig existing =
                deliveryProviderConfigMapper.findByWorkspaceChannel(workspaceId, channel.token());
        deliveryProviderConfigMapper.delete(workspaceId, channel.token());
        if (existing != null) {
            if (!isBlank(existing.getCredentialRef())) {
                deliveryProviderSecretCipher.deleteCredentialReference(workspaceId, existing.getCredentialRef());
            }
            if (!isBlank(existing.getWebhookSecretRef())) {
                deliveryProviderSecretCipher.deleteWebhookSecretReference(workspaceId, existing.getWebhookSecretRef());
            }
        }
        auditService.record("workspace.delivery_provider.delete", "workspace", workspaceId, null,
                "Removed delivery provider settings", null);
    }

    private ResolvedDeliveryProvider resolveEsp(DeliveryProviderConfig config) {
        if (isBlank(config.getCredentialRef())) {
            throw new DeliveryProviderException("Delivery provider credential is not configured");
        }
        String apiKey = deliveryProviderSecretCipher.decryptCredential(
                config.getWorkspaceId(), config.getCredentialRef());
        if (isBlank(apiKey)) {
            throw new DeliveryProviderException("Delivery provider credential is not configured");
        }
        return new ResolvedDeliveryProvider(
                config.getProvider(),
                DeliveryChannel.fromToken(config.getChannel()),
                config.getWorkspaceId(),
                config.getEndpoint(),
                config.getFromAddress(),
                config.getFromName(),
                DeliveryCredentials.of(Map.of(CREDENTIAL_KEY_API, apiKey)),
                config.isIdempotentSubmission(),
                targetFingerprint(config),
                null);
    }

    private ResolvedDeliveryProvider resolveSms(DeliveryProviderConfig config) {
        if (!isEnabledSms(config)) {
            throw new DeliveryProviderException("No usable SMS transport is configured for delivery");
        }
        if (isBlank(config.getCredentialRef())) {
            throw new DeliveryProviderException("Delivery provider credential is not configured");
        }
        String apiKey = deliveryProviderSecretCipher.decryptCredential(
                config.getWorkspaceId(), config.getCredentialRef());
        if (isBlank(apiKey)) {
            throw new DeliveryProviderException("Delivery provider credential is not configured");
        }
        return new ResolvedDeliveryProvider(
                config.getProvider(),
                DeliveryChannel.SMS,
                config.getWorkspaceId(),
                config.getEndpoint(),
                config.getFromAddress(),
                config.getFromName(),
                DeliveryCredentials.of(Map.of(CREDENTIAL_KEY_API, apiKey)),
                config.isIdempotentSubmission(),
                targetFingerprint(config),
                null);
    }

    private static String targetFingerprint(DeliveryProviderConfig config) {
        return DeliveryTargetFingerprint.create(
                config.getProvider(),
                "delivery-provider:" + config.getId() + ":" + config.getConfigGeneration(),
                String.valueOf(config.getEndpoint())
                        + "|account=" + String.valueOf(config.getFromAddress()),
                config.getCredentialRef());
    }

    private static String smtpEndpointIdentity(ResolvedMailConfig config) {
        return "smtp://" + config.host() + ":" + config.port()
                + "|username=" + String.valueOf(config.username())
                + "|from=" + String.valueOf(config.fromAddress())
                + "|starttls=" + config.starttls()
                + "|ssl=" + config.ssl()
                + "|auth=" + config.auth();
    }

    private String validateAndPopulateSmsHttp(DeliveryProviderConfigRequest request, DeliveryProviderConfig config) {
        config.setEndpoint(resolveEspEndpoint(request.getEndpoint()));
        config.setFromAddress(requireSenderId(request.getFromAddress()));
        config.setFromName(trimToNull(request.getFromName()));
        if (isBlank(request.getApiKey())) {
            return null;
        }
        String apiKey = request.getApiKey().trim();
        if (!API_KEY.matcher(apiKey).matches()) {
            throw new BadRequestException("Invalid delivery provider API key");
        }
        config.setCredentialLast4(last4(apiKey));
        return apiKey;
    }

    private String validateAndPopulateSmtp(DeliveryProviderConfigRequest request, DeliveryProviderConfig config) {
        config.setEndpoint(null);
        config.setFromAddress(trimToNull(request.getFromAddress()));
        config.setFromName(trimToNull(request.getFromName()));
        config.setCredentialRef(null);
        config.setCredentialLast4(null);
        return null;
    }

    private String validateAndPopulateHttpEsp(DeliveryProviderConfigRequest request, DeliveryProviderConfig config) {
        config.setEndpoint(resolveEspEndpoint(request.getEndpoint()));
        config.setFromAddress(requireFromAddress(request.getFromAddress()));
        config.setFromName(trimToNull(request.getFromName()));
        if (isBlank(request.getApiKey())) {
            return null;
        }
        String apiKey = request.getApiKey().trim();
        if (!API_KEY.matcher(apiKey).matches()) {
            throw new BadRequestException("Invalid delivery provider API key");
        }
        config.setCredentialLast4(last4(apiKey));
        return apiKey;
    }

    private String resolveEspEndpoint(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A provider endpoint is required");
        }
        String endpoint = requested.trim();
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new BadRequestException("Invalid provider endpoint");
        }
        URI uri = parseHttpsEndpoint(endpoint);
        if (uri == null || !aiEndpointAddressValidator.isFetchable(uri.getHost(), false)) {
            throw new BadRequestException("Invalid provider endpoint");
        }
        return endpoint;
    }

    private void deleteReplacedCredential(int workspaceId, DeliveryProviderConfig existing,
            DeliveryProviderConfig replacement, boolean sameProvider) {
        if (existing == null || isBlank(existing.getCredentialRef())) {
            return;
        }
        if (!sameProvider || !existing.getCredentialRef().equals(replacement.getCredentialRef())) {
            deliveryProviderSecretCipher.deleteCredentialReference(workspaceId, existing.getCredentialRef());
        }
        if (!sameProvider && !isBlank(existing.getWebhookSecretRef())) {
            deliveryProviderSecretCipher.deleteWebhookSecretReference(workspaceId, existing.getWebhookSecretRef());
        }
    }

    private static boolean isEnabledEsp(DeliveryProviderConfig config) {
        return config != null && config.isEnabled()
                && HttpEspDeliveryProvider.PROVIDER_ID.equals(config.getProvider());
    }

    private static boolean isEnabledSms(DeliveryProviderConfig config) {
        return config != null && config.isEnabled()
                && SmsHttpDeliveryProvider.PROVIDER_ID.equals(config.getProvider());
    }

    private static boolean requiresCredential(String provider) {
        return HttpEspDeliveryProvider.PROVIDER_ID.equals(provider)
                || SmsHttpDeliveryProvider.PROVIDER_ID.equals(provider);
    }

    private static DeliveryChannel resolveChannel(String requested) {
        DeliveryChannel channel;
        try {
            channel = DeliveryChannel.fromToken(requested);
        } catch (DeliveryProviderException exception) {
            throw new BadRequestException("Invalid delivery channel");
        }
        if (channel != DeliveryChannel.EMAIL && channel != DeliveryChannel.SMS) {
            throw new BadRequestException("Delivery channel " + channel + " is not supported yet");
        }
        return channel;
    }

    private static String resolveProvider(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A delivery provider is required");
        }
        String provider = requested.trim().toLowerCase(Locale.ROOT);
        if (!SmtpDeliveryProvider.PROVIDER_ID.equals(provider)
                && !HttpEspDeliveryProvider.PROVIDER_ID.equals(provider)
                && !SmsHttpDeliveryProvider.PROVIDER_ID.equals(provider)) {
            throw new BadRequestException("Unsupported delivery provider");
        }
        return provider;
    }

    private static void requireProviderMatchesChannel(DeliveryChannel channel, String provider) {
        boolean matches = switch (channel) {
            case EMAIL -> SmtpDeliveryProvider.PROVIDER_ID.equals(provider)
                    || HttpEspDeliveryProvider.PROVIDER_ID.equals(provider);
            case SMS -> SmsHttpDeliveryProvider.PROVIDER_ID.equals(provider);
            default -> false;
        };
        if (!matches) {
            throw new BadRequestException(
                    "Provider " + provider + " is not valid for the " + channel + " channel");
        }
    }

    private static String requireSenderId(String requested) {
        String value = trimToNull(requested);
        if (value == null || !SMS_SENDER_ID.matcher(value).matches()) {
            throw new BadRequestException("A valid SMS sender id is required");
        }
        return value;
    }

    /**
     * Validates an email envelope from-address to the same grade the DTO's {@code @Email} constraint
     * gave before validation moved here per channel: a non-empty local part, a single {@code @}, and a
     * dot-separated domain of label characters. A bare {@code a@} or {@code @host} is rejected.
     */
    private static String requireFromAddress(String requested) {
        String value = trimToNull(requested);
        if (value == null) {
            throw new BadRequestException("A from address is required");
        }
        if (value.length() > 320 || !EMAIL_ADDRESS.matcher(value).matches()) {
            throw new BadRequestException("A valid from address is required");
        }
        return value;
    }

    private static boolean endpointHostChanged(String storedEndpoint, String newEndpoint) {
        String storedHost = hostOf(storedEndpoint);
        String newHost = hostOf(newEndpoint);
        if (storedHost == null || newHost == null) {
            return !java.util.Objects.equals(storedHost, newHost);
        }
        return !storedHost.equals(newHost);
    }

    private static String hostOf(String endpoint) {
        if (isBlank(endpoint)) {
            return null;
        }
        try {
            String host = URI.create(endpoint.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static URI parseHttpsEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || isBlank(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String randomHex() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new DeliveryProviderException("Unable to hash webhook token", exception);
        }
    }

    private static String last4(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        return value.substring(value.length() - 4);
    }

    private static void requireEmail(DeliveryChannel channel) {
        if (channel != DeliveryChannel.EMAIL) {
            throw new DeliveryProviderException("Delivery channel " + channel + " is not supported yet");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
