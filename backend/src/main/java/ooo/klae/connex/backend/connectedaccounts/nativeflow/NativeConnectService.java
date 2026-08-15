package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountMode;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver.ProviderAccountIdentity;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenClient;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenException;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCaptureConnectionStateService;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Orchestrates Connex-managed installed-application authorization through loopback PKCE. */
@Service
@RequiredArgsConstructor
public class NativeConnectService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final String HELPER_RESOURCE =
        "connectedaccounts/connex-connect.mjs";

    private final ConnectedAccountProviders providers;
    private final NativeConnectSessionPersistence sessionPersistence;
    private final NativeConnectPkceSecretCipher pkceSecretCipher;
    private final ProviderTokenClient tokenClient;
    private final ProviderAccountIdentityResolver accountIdentityResolver;
    private final ProviderCaptureConnectionStateService captureConnectionStateService;
    private final WorkspaceService workspaceService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final MailProperties mailProperties;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;

    /** Creates a step-up-gated, ten-minute pairing credential for the current user. */
    public NativePairingResponse createPairing(String provider) {
        requireNativeProvider(provider);
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        String instanceBaseUrl = normalizedInstanceBaseUrl();
        String pairingCode = NativeConnectPkce.randomSecret();
        Instant expiresAt = Instant.now(clock).plus(SESSION_TTL);
        boolean superseded = tenantWorkScope.unrouted(() -> sessionPersistence.create(
            userId,
            provider,
            NativeConnectPkce.hash(pairingCode),
            LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
        if (superseded) {
            auditFailure(userId, provider, "superseded");
        }
        auditSuccess(
            "user.connection.request",
            userId,
            provider,
            "Started a managed " + provider + " connection");
        return new NativePairingResponse(
            pairingCode,
            expiresAt,
            instanceBaseUrl,
            "node connex-connect.mjs --instance " + instanceBaseUrl
                + " --pairing-code " + pairingCode);
    }

    /** Returns only the current user's latest pairing state for the requested provider. */
    public NativePairingStatusResponse pairingStatus(String provider) {
        requireNativeProvider(provider);
        int userId = workspaceService.getCurrentUserId();
        NativeConnectPoll poll = tenantWorkScope.unrouted(
            () -> sessionPersistence.poll(userId, provider));
        NativeConnectSession session = poll.session();
        if (poll.expiredTransition()) {
            auditFailure(userId, provider, "expired");
        }
        if (session == null) {
            return new NativePairingStatusResponse("none", null, null);
        }
        return new NativePairingStatusResponse(
            session.getStatus(),
            session.getErrorCode(),
            instant(session.getExpiresAt()));
    }

    /** Cancels only the current user's active pairing for the requested provider. */
    public void cancelPairing(String provider) {
        requireNativeProvider(provider);
        int userId = workspaceService.getCurrentUserId();
        boolean cancelled = tenantWorkScope.unrouted(
            () -> sessionPersistence.cancel(userId, provider));
        if (cancelled) {
            auditFailure(userId, provider, "cancelled");
        }
    }

    /** Claims a pairing credential and returns a provider authorize URL to the local helper. */
    public NativePrepareResponse prepare(NativePrepareRequest request) {
        return withoutRequestActor(() -> prepareBearerHandoff(request));
    }

    private NativePrepareResponse prepareBearerHandoff(NativePrepareRequest request) {
        String redirectUri = validatedRedirectUri(request.redirectUri());
        byte[] pairingCodeHash = NativeConnectPkce.hash(request.pairingCode());
        NativeConnectSession candidate = tenantWorkScope.unrouted(
            () -> sessionPersistence.findByPairingCodeHash(pairingCodeHash));
        if (candidate == null) {
            throw new NativeConnectException(
                "invalid_pairing_code", "Pairing code is invalid");
        }
        requireNativeProvider(candidate.getProvider());
        String verifier = NativeConnectPkce.randomSecret();
        String state = NativeConnectPkce.randomSecret();
        String handoffTicket = NativeConnectPkce.randomSecret();
        String authorizeUrl = providers.nativeAuthorizeUrl(
            candidate.getProvider(),
            redirectUri,
            state,
            NativeConnectPkce.challenge(verifier));
        NativeConnectSession prepared = tenantWorkScope.unrouted(
            () -> sessionPersistence.prepare(
                pairingCodeHash,
                NativeConnectPkce.hash(handoffTicket),
                NativeConnectPkce.hash(state),
                verifier,
                redirectUri));
        auditSuccess(
            "user.connection.request",
            prepared.getUserId(),
            prepared.getProvider(),
            "Prepared a managed " + prepared.getProvider() + " authorization");
        return new NativePrepareResponse(
            authorizeUrl,
            handoffTicket,
            instant(prepared.getExpiresAt()));
    }

    /** Exchanges one claimed helper handoff and stores the resulting per-user credential. */
    public NativeCompleteResponse complete(NativeCompleteRequest request) {
        return withoutRequestActor(() -> completeBearerHandoff(request));
    }

    private NativeCompleteResponse completeBearerHandoff(NativeCompleteRequest request) {
        byte[] handoffTicketHash = NativeConnectPkce.hash(request.handoffTicket());
        NativeConnectSession candidate = tenantWorkScope.unrouted(
            () -> sessionPersistence.findByHandoffTicketHash(handoffTicketHash));
        if (candidate == null) {
            throw new NativeConnectException(
                "invalid_handoff_ticket", "Handoff ticket is invalid");
        }
        requireNativeProvider(candidate.getProvider());
        NativeConnectSession session = tenantWorkScope.unrouted(
            () -> sessionPersistence.claimForExchange(handoffTicketHash));
        if (!NativeConnectPkce.matches(session.getStateHash(), request.state())) {
            throw failClaim(session, "state_mismatch");
        }
        String verifier;
        try {
            verifier = tenantWorkScope.unrouted(
                () -> pkceSecretCipher.read(
                    session.getProvider(),
                    session.getUserId(),
                    session.getVerifierRef()));
        } catch (RuntimeException exception) {
            throw failClaim(session, "pkce_verifier_unavailable");
        }
        try {
            ProviderTokenResponse tokens = tokenClient.exchange(
                providers.tokenUri(session.getProvider()),
                exchangeForm(session, request.code(), verifier));
            if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
                throw new ProviderTokenException(
                    "no_offline_access", "Provider withheld a refresh token");
            }
            ProviderAccountIdentity identity = accountIdentityResolver.resolve(
                session.getProvider(), tokens.idToken());
            String grantedScopes = tokens.scope() == null
                ? providers.scopes(session.getProvider())
                : tokens.scope();
            boolean created = tenantWorkScope.unrouted(
                () -> sessionPersistence.storeConnectionAndComplete(
                    session, tokens, identity, grantedScopes));
            captureConnectionStateService.reconcile(
                session.getUserId(), session.getProvider());
            auditSuccess(
                "user.connection.connect",
                session.getUserId(),
                session.getProvider(),
                (created ? "Connected a managed " : "Reconnected a managed ")
                    + session.getProvider() + " account");
            return new NativeCompleteResponse("connected");
        } catch (ProviderTokenException exception) {
            throw failClaim(session, exception.getCode());
        } catch (ConflictException exception) {
            throw failClaim(session, "connection_conflict");
        } catch (RuntimeException exception) {
            throw failClaim(session, "connection_failed");
        }
    }

    /** Reads the bundled zero-dependency helper script served to authenticated users. */
    public String helperScript() {
        try (var input = new ClassPathResource(HELPER_RESOURCE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Native connection helper is unavailable", exception);
        }
    }

    private Map<String, String> exchangeForm(
            NativeConnectSession session,
            String code,
            String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", verifier);
        form.put("client_id", providers.effectiveClientId(session.getProvider()));
        form.put("redirect_uri", session.getRedirectUri());
        String clientSecret = providers.effectiveClientSecret(session.getProvider());
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.put("client_secret", clientSecret);
        }
        return form;
    }

    private void requireNativeProvider(String provider) {
        if (!providers.isSupported(provider)) {
            throw new ResourceNotFoundException("Unknown provider: " + provider);
        }
        if (providers.mode(provider) != ConnectedAccountMode.MANAGED) {
            throw new NativeConnectException(
                "custom_connection_flow",
                "This instance uses the Custom/BYO connection flow for " + provider);
        }
        if (!providers.isEnabled(provider)) {
            throw new NativeConnectException(
                "managed_identity_unavailable",
                "The Connex-managed identity for " + provider + " is unavailable in this build");
        }
    }

    private NativeConnectException failClaim(
            NativeConnectSession session,
            String errorCode) {
        String reportedCode = errorCode;
        try {
            tenantWorkScope.unrouted(() -> {
                sessionPersistence.failExchange(session, errorCode);
                return null;
            });
        } catch (RuntimeException exception) {
            reportedCode = "connection_failed";
        }
        auditFailure(session.getUserId(), session.getProvider(), reportedCode);
        return new NativeConnectException(
            reportedCode, "Native provider authorization failed");
    }

    private String validatedRedirectUri(String value) {
        try {
            URI uri = new URI(value).parseServerAuthority();
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (!"http".equals(uri.getScheme())
                    || !loopback
                    || uri.getPort() < 1024
                    || uri.getPort() > 65535
                    || !"/callback".equals(uri.getRawPath())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                throw invalidRedirectUri();
            }
            return uri.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw invalidRedirectUri();
        }
    }

    private static NativeConnectException invalidRedirectUri() {
        return new NativeConnectException(
            "invalid_redirect_uri", "Loopback redirect URI is invalid");
    }

    private void auditSuccess(
            String action,
            int userId,
            String provider,
            String summary) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordScoped(
                action, "user", userId, null, null, provider, summary, null);
            return null;
        });
    }

    private void auditFailure(int userId, String provider, String errorCode) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordFailureScoped(
                "user.connection.connect_failed",
                "user",
                userId,
                null,
                null,
                provider,
                "Managed provider authorization failed",
                errorCode);
            return null;
        });
    }

    private String normalizedInstanceBaseUrl() {
        String value = mailProperties.getAppBaseUrl();
        if (value == null || value.isBlank()) {
            throw unavailableInstanceBaseUrl();
        }
        try {
            URI uri = new URI(value).parseServerAuthority();
            String host = uri.getHost();
            boolean secureTransport = "https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && isInstanceLoopbackHost(host));
            boolean rootPath = uri.getRawPath() == null
                || uri.getRawPath().isEmpty()
                || "/".equals(uri.getRawPath());
            if (!secureTransport
                    || host == null
                    || host.isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !rootPath
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw unavailableInstanceBaseUrl();
            }
            String normalized = uri.toASCIIString();
            return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw unavailableInstanceBaseUrl();
        }
    }

    private static NativeConnectException unavailableInstanceBaseUrl() {
        return new NativeConnectException(
            "instance_base_url_unavailable", "Instance base URL is unavailable");
    }

    private static boolean isInstanceLoopbackHost(String host) {
        return "localhost".equals(host)
            || "127.0.0.1".equals(host)
            || "[::1]".equals(host);
    }

    private static <T> T withoutRequestActor(Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(
                SecurityContextHolder.createEmptyContext());
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
