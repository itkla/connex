package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.ai.AiProviderSecretCipher;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.dto.AiProviderConfigDto;
import ooo.klae.connex.backend.dto.AiProviderConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiProviderConfigMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Org-administrator management of an organization's BYOP AI provider settings.
 * The request is addressed by an acting workspace, but authorization is
 * organization-scoped through {@code OrgMemberService}. Provider credentials are
 * stored as one envelope-encrypted JSON bundle and never returned to clients.
 */
@Service
@RequiredArgsConstructor
public class AiProviderConfigService implements AiProviderReadiness {
    private static final String PROVIDER_BEDROCK = "bedrock";
    private static final String PROVIDER_AZURE_OPENAI = "azure_openai";
    private static final Pattern BEDROCK_MODEL_ID = Pattern.compile(
            "^(?!.*\\.\\.)([a-z]{2,4}\\.)?[A-Za-z0-9]([A-Za-z0-9._-]{0,80})?(:[A-Za-z0-9.\\-]{1,20})?$");
    private static final Pattern ACCESS_KEY_ID = Pattern.compile("^[A-Za-z0-9]{8,128}$");
    private static final Pattern SECRET_ACCESS_KEY = Pattern.compile("^[A-Za-z0-9+/=]{1,255}$");
    private static final Pattern SESSION_TOKEN = Pattern.compile("^[A-Za-z0-9+/=._\\-]{1,4096}$");
    private static final Set<String> BEDROCK_REGIONS = Set.of(
            "us-east-1",
            "us-west-2",
            "ap-northeast-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "eu-central-1",
            "eu-west-1",
            "eu-west-3");

    private final AiProviderConfigMapper aiProviderConfigMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrgMemberService orgMemberService;
    private final AiProviderSecretCipher aiProviderSecretCipher;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final ObjectMapper objectMapper;

    /**
     * Returns the AI provider settings for the acting workspace's organization.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @return the masked provider settings view
     */
    public AiProviderConfigDto getForWorkspace(int workspaceId, int actorId) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        return AiProviderConfigDto.from(aiProviderConfigMapper.findByOrg(orgId));
    }

    /**
     * Creates or updates the AI provider settings for the acting workspace's
     * organization. Blank credentials preserve the stored credential reference.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @param request the submitted provider settings
     * @return the saved masked provider settings view
     */
    @Transactional
    public AiProviderConfigDto save(int workspaceId, int actorId, AiProviderConfigRequest request) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);

        String provider = resolveProvider(request.getProvider());
        String region = resolveBedrockRegion(request.getRegion());
        String modelId = resolveBedrockClaudeModelId(request.getModelId());
        AiProviderConfig existing = aiProviderConfigMapper.findByOrg(orgId);
        boolean suppliedCredential = !isBlank(request.getSecretAccessKey());
        boolean storedCredential = existing != null && !isBlank(existing.getCredentialRef());

        if (request.isEnabled() && (!request.isNoTrainingAttested() || (!suppliedCredential && !storedCredential))) {
            throw new BadRequestException(
                    "A no-training/no-retention attestation and stored credentials are required before enabling AI");
        }

        AiProviderConfig config = new AiProviderConfig();
        config.setOrgId(orgId);
        config.setProvider(provider);
        config.setRegion(region);
        config.setModelId(modelId);
        config.setCredentialRef(existing == null ? null : existing.getCredentialRef());
        config.setCredentialLast4(existing == null ? null : existing.getCredentialLast4());
        if (suppliedCredential) {
            String secretAccessKey = request.getSecretAccessKey();
            String accessKeyId = request.getAccessKeyId();
            String sessionToken = isBlank(request.getSessionToken()) ? null : request.getSessionToken();
            requireValidProviderCredentials(accessKeyId, secretAccessKey, sessionToken);
            config.setCredentialRef(aiProviderSecretCipher.encryptCredential(orgId,
                    credentialBundleJson(accessKeyId, secretAccessKey, sessionToken)));
            config.setCredentialLast4(last4(secretAccessKey));
        }
        config.setNoTrainingAttested(request.isNoTrainingAttested());
        config.setAttestedAt(resolveAttestedAt(existing, request.isNoTrainingAttested()));
        config.setEnabled(request.isEnabled());

        aiProviderConfigMapper.upsert(config);
        deleteReplacedSecretReference(existing, config);
        auditService.record("org.ai_provider.save", "organization", orgId, provider,
                "Updated AI provider configuration", null);
        return getForWorkspace(workspaceId, actorId);
    }

    /**
     * Removes the AI provider settings and deletes the stored credential reference.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     */
    @Transactional
    public void revoke(int workspaceId, int actorId) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AiProviderConfig existing = aiProviderConfigMapper.findByOrg(orgId);
        aiProviderConfigMapper.deleteByOrg(orgId);
        if (existing != null && !isBlank(existing.getCredentialRef())) {
            aiProviderSecretCipher.deleteCredentialReference(orgId, existing.getCredentialRef());
        }
        auditService.record("org.ai_provider.revoke", "organization", orgId, null,
                "Revoked AI provider configuration", null);
    }

    @Override
    public boolean isReadyForOrg(int orgId) {
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        return isReady(config);
    }

    /**
     * Resolves the configured organization provider and decrypts credentials for provider use.
     * @param orgId the organization
     * @return resolved provider configuration with decrypted credentials
     */
    public ResolvedAiProvider resolveForOrg(int orgId) {
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        if (!isReady(config)) {
            throw new ForbiddenException("AI provider is not available for this organization");
        }
        return new ResolvedAiProvider(config.getProvider(), config.getRegion(), config.getModelId(),
                decryptCredentials(orgId, config.getCredentialRef()));
    }

    private boolean isReady(AiProviderConfig config) {
        return config != null
                && PROVIDER_BEDROCK.equals(config.getProvider())
                && config.isEnabled()
                && !isBlank(config.getCredentialRef())
                && config.isNoTrainingAttested()
                && BEDROCK_REGIONS.contains(config.getRegion())
                && isSupportedBedrockClaudeModelId(config.getModelId())
                && aiProviderSecretCipher.isAvailable();
    }

    private AiCredentials decryptCredentials(int orgId, String credentialRef) {
        try {
            String bundleJson = aiProviderSecretCipher.decryptCredential(orgId, credentialRef);
            if (isBlank(bundleJson)) {
                throw new AiProviderException("AI provider credentials are invalid");
            }
            CredentialBundle bundle = objectMapper.readValue(bundleJson, CredentialBundle.class);
            return new AiCredentials(requireCredentialValue(bundle.accessKeyId()),
                    requireCredentialValue(bundle.secretAccessKey()), trimToNull(bundle.sessionToken()));
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("AI provider credentials are invalid");
        }
    }

    private static String requireCredentialValue(String value) {
        if (isBlank(value)) {
            throw new AiProviderException("AI provider credentials are invalid");
        }
        return value.trim();
    }

    private record CredentialBundle(String accessKeyId, String secretAccessKey, String sessionToken) {
    }

    private int requireAdministrableOrg(int workspaceId, int actorId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return orgId;
    }

    private static String resolveProvider(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("AI provider is required");
        }
        String provider = requested.trim().toLowerCase(Locale.ROOT);
        if (PROVIDER_AZURE_OPENAI.equals(provider)) {
            throw new BadRequestException("The azure_openai provider is not yet available");
        }
        if (!PROVIDER_BEDROCK.equals(provider)) {
            throw new BadRequestException("Unsupported AI provider");
        }
        return provider;
    }

    private static String resolveBedrockRegion(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A Bedrock region is required");
        }
        String region = requested.trim().toLowerCase(Locale.ROOT);
        if (!BEDROCK_REGIONS.contains(region)) {
            throw new BadRequestException("Unsupported Bedrock region");
        }
        return region;
    }

    private static String resolveBedrockClaudeModelId(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A Bedrock Claude model id is required");
        }
        String modelId = requested.trim();
        if (!BEDROCK_MODEL_ID.matcher(modelId).matches()) {
            throw new BadRequestException("Invalid Bedrock model id");
        }
        if (!modelId.toLowerCase(Locale.ROOT).contains("claude")) {
            throw new BadRequestException("The Bedrock provider supports Claude models only");
        }
        return modelId;
    }

    private static boolean isSupportedBedrockClaudeModelId(String modelId) {
        return !isBlank(modelId)
                && BEDROCK_MODEL_ID.matcher(modelId).matches()
                && modelId.toLowerCase(Locale.ROOT).contains("claude");
    }

    private static void requireValidProviderCredentials(String accessKeyId, String secretAccessKey,
            String sessionToken) {
        if (isBlank(accessKeyId) || !ACCESS_KEY_ID.matcher(accessKeyId).matches()
                || isBlank(secretAccessKey) || !SECRET_ACCESS_KEY.matcher(secretAccessKey).matches()
                || sessionToken != null && !SESSION_TOKEN.matcher(sessionToken).matches()) {
            throw new BadRequestException("Invalid provider credentials");
        }
    }

    private String credentialBundleJson(String accessKeyId, String secretAccessKey, String sessionToken) {
        Map<String, String> bundle = new LinkedHashMap<>();
        bundle.put("accessKeyId", accessKeyId);
        bundle.put("secretAccessKey", secretAccessKey);
        if (sessionToken != null) {
            bundle.put("sessionToken", sessionToken);
        }
        try {
            return objectMapper.writeValueAsString(bundle);
        } catch (Exception exception) {
            throw new BadRequestException("Invalid provider credentials");
        }
    }

    private static LocalDateTime resolveAttestedAt(AiProviderConfig existing, boolean requestedAttestation) {
        if (!requestedAttestation) {
            return null;
        }
        if (existing != null && existing.isNoTrainingAttested() && existing.getAttestedAt() != null) {
            return existing.getAttestedAt();
        }
        return LocalDateTime.now();
    }

    private void deleteReplacedSecretReference(AiProviderConfig existing, AiProviderConfig replacement) {
        if (existing == null || isBlank(existing.getCredentialRef())
                || Objects.equals(existing.getCredentialRef(), replacement.getCredentialRef())) {
            return;
        }
        aiProviderSecretCipher.deleteCredentialReference(existing.getOrgId(), existing.getCredentialRef());
    }

    private static String last4(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
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
