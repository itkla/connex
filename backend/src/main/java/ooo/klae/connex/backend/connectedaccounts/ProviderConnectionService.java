package ooo.klae.connex.backend.connectedaccounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver.ProviderAccountIdentity;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCaptureConnectionStateService;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Manages a user's own connections to external mail/calendar providers (#60 WS1): authorize-URL
 * minting, the OAuth callback exchange, and the pause/resume/disconnect lifecycle.
 *
 * <p>This is deliberately not {@code oauth2Login}: the browser is already authenticated, no
 * session is established, and the exchanged tokens are persisted (encrypted, user-scoped) rather
 * than discarded. The OAuth {@code state} is a single-use value bound to the caller's HTTP
 * session, so a forged or replayed callback cannot attach a token bundle to another user.
 * All provider endpoints are fixed hosts from {@link ConnectedAccountProviders}; nothing in the
 * flow fetches a caller-supplied URL. Everything here is self-scoped — the acting user comes
 * from the security context and rows are only ever addressed by {@code (userId, provider)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConnectionService {

    private static final String STATE_SESSION_ATTRIBUTE = "connex.connectedAccounts.pendingState";
    private static final long STATE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProviderConnectionMapper connectionMapper;
    private final ConnectedAccountProviders providers;
    private final ProviderTokenClient tokenClient;
    private final ProviderCredentialPersistence credentialPersistence;
    private final ProviderConnectionLifecycleService lifecycleService;
    private final ProviderConnectionMutation connectionMutation;
    private final WorkspaceService workspaceService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final MailProperties mailProperties;
    private final TenantWorkScope tenantWorkScope;
    private final ProviderCaptureConnectionStateService captureConnectionStateService;
    private final ProviderAccountIdentityResolver accountIdentityResolver;

    /** The current user's connections, masked for display. */
    public List<ProviderConnectionDto> getForCurrentUser() {
        int userId = workspaceService.getCurrentUserId();
        return tenantWorkScope.unrouted(
            () -> connectionMapper.getByUserId(userId).stream()
                .map(ProviderConnectionDto::from)
                .toList());
    }

    /**
     * Mints the provider consent URL for the current user. Step-up gated: initiating a connection
     * grants Connex durable mailbox access, so it requires a recent authentication like every
     * other credential mutation. The returned URL carries a session-bound single-use state.
     */
    public String beginAuthorization(String provider) {
        requireEnabled(provider);
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        String state = randomState();
        session(true).setAttribute(STATE_SESSION_ATTRIBUTE,
            sha256(state) + "|" + provider + "|" + Instant.now().toEpochMilli());
        auditService.record("user.connection.request", "user", userId, provider,
            "Started connecting a " + provider + " account", null);
        return providers.authorizeUrl(provider, redirectUri(provider), state);
    }

    /**
     * Completes the OAuth callback: validates and consumes the session-bound state, exchanges the
     * code server-side, stores the encrypted token bundle under the user scope, and upserts the
     * connection row. Returns the browser redirect target (always the trusted app base URL).
     *
     * <p>The external code exchange completes before the control-catalog credential transaction,
     * so provider latency never holds a database transaction.
     */
    public String completeCallback(String provider, String code, String state, String providerError) {
        requireEnabled(provider);
        int userId = workspaceService.getCurrentUserId();
        if (!consumePendingState(provider, state)) {
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Rejected a provider callback with an invalid or expired state", null);
            return connectionsUrl("error", "state");
        }
        if (providerError != null || code == null || code.isBlank()) {
            return connectionsUrl("error", "denied");
        }
        try {
            return exchangeAndStore(provider, code, userId);
        } catch (ProviderTokenException e) {
            log.warn("Token exchange with {} failed: {}", provider, e.getCode());
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Token exchange failed: " + e.getCode(), null);
            return connectionsUrl("error", "exchange");
        } catch (RuntimeException e) {
            log.warn("Completing a {} connection failed: {}", provider, e.getClass().getSimpleName());
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Completing the connection failed: " + e.getClass().getSimpleName(), null);
            return connectionsUrl("error", "exchange");
        }
    }

    /**
     * The post-validation half of the callback. Anything thrown here is turned into an
     * {@code error=exchange} redirect by the caller — after the user consented at the provider,
     * a raw error page must never be the answer to a routine failure.
     */
    private String exchangeAndStore(String provider, String code, int userId) {
        ProviderTokenResponse tokens =
            tokenClient.exchange(providers.tokenUri(provider), exchangeForm(provider, code));
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Provider withheld a refresh token", null);
            return connectionsUrl("error", "no_offline_access");
        }

        boolean created = tenantWorkScope.unrouted(
            () -> {
                ProviderAccountIdentity identity =
                    accountIdentityResolver.resolve(provider, tokens.idToken());
                return credentialPersistence.storeConnection(
                    userId,
                    provider,
                    tokens,
                    identity.accountId(),
                    identity.email(),
                    tokens.scope() == null ? providers.scopes(provider) : tokens.scope());
            });
        captureConnectionStateService.reconcile(userId, provider);
        auditService.record("user.connection.connect", "user", userId, provider,
            (created ? "Connected a " : "Reconnected a ") + provider + " account", null);
        return connectionsUrl("connected", provider);
    }

    /** Pauses an active connection; sync workstreams must skip paused connections. */
    public ProviderConnectionDto pause(String provider) {
        return transition(provider, "connected", "paused", "user.connection.pause", "Paused");
    }

    /** Resumes a paused connection. */
    public ProviderConnectionDto resume(String provider) {
        return transition(provider, "paused", "connected", "user.connection.resume", "Resumed");
    }

    /**
     * Starts a durable disconnect that stops claims, purges every tenant catalog, attempts
     * provider revocation, and only then destroys the generation-bound local credential.
     * Step-up is required because this removes both retained content and durable access.
     */
    public void disconnect(String provider) {
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        requireSupported(provider);
        ProviderConnection connection = tenantWorkScope.unrouted(
            () -> connectionMutation.beginDisconnect(userId, provider));
        if (!lifecycleService.process(connection)) {
            throw new ConflictException(
                "Provider disconnect cleanup is pending; retry after the current purge finishes");
        }
        auditService.record("user.connection.disconnect", "user", userId, provider,
            "Started disconnect and purge for the " + provider + " account", null);
    }

    private ProviderConnectionDto transition(String provider, String from, String to,
            String auditAction, String verb) {
        int userId = workspaceService.getCurrentUserId();
        requireSupported(provider);
        ProviderConnectionDto result = tenantWorkScope.unrouted(
            () -> connectionMutation.transition(userId, provider, from, to));
        captureConnectionStateService.reconcile(userId, provider);
        auditService.record(auditAction, "user", userId, provider,
            verb + " the " + provider + " connection", null);
        return result;
    }

    private Map<String, String> exchangeForm(String provider, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", providers.effectiveClientId(provider));
        form.put("client_secret", providers.effectiveClientSecret(provider));
        form.put("redirect_uri", redirectUri(provider));
        return form;
    }

    private boolean consumePendingState(String provider, String state) {
        HttpSession session = session(false);
        if (session == null || state == null || state.isBlank()) {
            return false;
        }
        Object attribute = session.getAttribute(STATE_SESSION_ATTRIBUTE);
        session.removeAttribute(STATE_SESSION_ATTRIBUTE);
        if (!(attribute instanceof String stored)) {
            return false;
        }
        String[] parts = stored.split("\\|", 3);
        if (parts.length != 3 || !parts[1].equals(provider)) {
            return false;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.now().toEpochMilli() - issuedAt > STATE_TTL_MILLIS) {
            return false;
        }
        return MessageDigest.isEqual(
            parts[0].getBytes(StandardCharsets.UTF_8), sha256(state).getBytes(StandardCharsets.UTF_8));
    }

    private void requireSupported(String provider) {
        if (!providers.isSupported(provider)) {
            throw new ResourceNotFoundException("Unknown provider: " + provider);
        }
    }

    private void requireEnabled(String provider) {
        requireSupported(provider);
        if (providers.mode(provider) == ConnectedAccountMode.MANAGED) {
            throw new BadRequestException(
                "This instance uses the Connex-managed connection flow for " + provider);
        }
        if (!providers.isEnabled(provider)) {
            throw new BadRequestException("This provider is not available on this instance");
        }
    }

    private String redirectUri(String provider) {
        return mailProperties.getAppBaseUrl() + "/api/account/connections/callback/" + provider;
    }

    private String connectionsUrl(String key, String value) {
        return mailProperties.getAppBaseUrl() + "/account/connections?" + key + "=" + value;
    }

    private HttpSession session(boolean create) {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new BadRequestException("No active session");
        }
        return attributes.getRequest().getSession(create);
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
