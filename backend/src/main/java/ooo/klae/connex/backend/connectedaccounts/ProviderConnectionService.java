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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;

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
    private final UserProviderSecretCipher secretCipher;
    private final ProviderTokenClient tokenClient;
    private final WorkspaceService workspaceService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;

    /** The current user's connections, masked for display. */
    public List<ProviderConnectionDto> getForCurrentUser() {
        int userId = workspaceService.getCurrentUserId();
        return connectionMapper.getByUserId(userId).stream().map(ProviderConnectionDto::from).toList();
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
     * <p>Deliberately not one transaction: the code exchange is an external call that must not
     * hold a pooled connection, and the secret write + row upsert are each atomic on their own.
     * A crash between them leaves only an orphaned secret that the next reconnect overwrites
     * (the secret store upserts per user + purpose).
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
        ProviderTokenResponse tokens;
        try {
            tokens = tokenClient.exchange(providers.tokenUri(provider), exchangeForm(provider, code));
        } catch (ProviderTokenException e) {
            log.warn("Token exchange with {} failed: {}", provider, e.getCode());
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Token exchange failed: " + e.getCode(), null);
            return connectionsUrl("error", "exchange");
        }
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            auditService.record("user.connection.connect_failed", "user", userId, provider,
                "Provider withheld a refresh token", null);
            return connectionsUrl("error", "no_offline_access");
        }

        String reference = secretCipher.encryptTokenBundle(provider, userId, bundleJson(tokens));
        ProviderConnection existing = connectionMapper.getByUserAndProvider(userId, provider);
        ProviderConnection connection = existing == null ? new ProviderConnection() : existing;
        connection.setUserId(userId);
        connection.setProvider(provider);
        connection.setStatus("connected");
        connection.setProviderAccountEmail(emailFromIdToken(tokens.idToken()));
        connection.setGrantedScopes(tokens.scope() == null ? providers.scopes(provider) : tokens.scope());
        connection.setCredentialRef(reference);
        connection.setErrorCode(null);
        if (existing == null) {
            connectionMapper.insert(connection);
        } else {
            connectionMapper.update(connection);
        }
        auditService.record("user.connection.connect", "user", userId, provider,
            (existing == null ? "Connected a " : "Reconnected a ") + provider + " account", null);
        return connectionsUrl("connected", provider);
    }

    /** Pauses an active connection; sync workstreams must skip paused connections. */
    @Transactional
    public ProviderConnectionDto pause(String provider) {
        return transition(provider, "connected", "paused", "user.connection.pause", "Paused");
    }

    /** Resumes a paused connection. */
    @Transactional
    public ProviderConnectionDto resume(String provider) {
        return transition(provider, "paused", "connected", "user.connection.resume", "Resumed");
    }

    /**
     * Disconnects: best-effort revocation at the provider, removal of the connection row, and
     * deletion of the stored token bundle. Step-up gated — this destroys a durable credential.
     *
     * <p>Deliberately not one transaction: revocation reads the bundle through the secret store,
     * whose own transactional failure (e.g. a dangling reference) would poison an enclosing
     * transaction even when caught — and local disconnection must never be blocked by a broken
     * secret. Row-then-secret ordering means a crash in between leaves only an invisible orphaned
     * secret, never a row pointing at a deleted credential.
     */
    public void disconnect(String provider) {
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        ProviderConnection connection = require(userId, provider);
        revokeBestEffort(provider, userId, connection);
        connectionMapper.delete(userId, provider);
        if (connection.getCredentialRef() != null) {
            secretCipher.deleteTokenBundleReference(provider, userId, connection.getCredentialRef());
        }
        auditService.record("user.connection.disconnect", "user", userId, provider,
            "Disconnected the " + provider + " account", null);
    }

    private ProviderConnectionDto transition(String provider, String from, String to,
            String auditAction, String verb) {
        int userId = workspaceService.getCurrentUserId();
        ProviderConnection connection = require(userId, provider);
        if (!from.equals(connection.getStatus())) {
            throw new BadRequestException("Connection is " + connection.getStatus() + ", not " + from);
        }
        connection.setStatus(to);
        connectionMapper.update(connection);
        auditService.record(auditAction, "user", userId, provider,
            verb + " the " + provider + " connection", null);
        return ProviderConnectionDto.from(connectionMapper.getByUserAndProvider(userId, provider));
    }

    private void revokeBestEffort(String provider, int userId, ProviderConnection connection) {
        String revokeUri = providers.revokeUri(provider);
        if (revokeUri == null || connection.getCredentialRef() == null) {
            return;
        }
        try {
            JsonNode bundle = objectMapper.readTree(
                secretCipher.decryptTokenBundle(provider, userId, connection.getCredentialRef()));
            if (bundle.hasNonNull("refreshToken")) {
                tokenClient.revoke(revokeUri, bundle.get("refreshToken").asString());
            }
        } catch (RuntimeException e) {
            log.warn("Best-effort revocation for {} connection failed: {}", provider, e.toString());
        }
    }

    private Map<String, String> exchangeForm(String provider, String code) {
        ConnectedAccountProperties.Provider client = providers.client(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", client.getClientId());
        form.put("client_secret", client.getClientSecret());
        form.put("redirect_uri", redirectUri(provider));
        return form;
    }

    private String bundleJson(ProviderTokenResponse tokens) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("refreshToken", tokens.refreshToken());
        bundle.put("accessToken", tokens.accessToken());
        if (tokens.expiresIn() != null) {
            bundle.put("accessTokenExpiresAt", Instant.now().plusSeconds(tokens.expiresIn()).toString());
        }
        if (tokens.scope() != null) {
            bundle.put("scope", tokens.scope());
        }
        bundle.put("obtainedAt", Instant.now().toString());
        return objectMapper.writeValueAsString(bundle);
    }

    /**
     * Extracts the account email from the id token for display. The token was received directly
     * from the provider's token endpoint over TLS in the same exchange, so decoding its payload
     * without signature verification is acceptable for non-authorizing display metadata — it is
     * never used to authenticate or link accounts.
     */
    private String emailFromIdToken(String idToken) {
        if (idToken == null) {
            return null;
        }
        try {
            String[] parts = idToken.split("\\.", -1);
            if (parts.length < 2) {
                return null;
            }
            JsonNode claims = objectMapper.readTree(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return claims.hasNonNull("email") ? claims.get("email").asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
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

    private ProviderConnection require(int userId, String provider) {
        requireSupported(provider);
        ProviderConnection connection = connectionMapper.getByUserAndProvider(userId, provider);
        if (connection == null) {
            throw new ResourceNotFoundException("No " + provider + " connection");
        }
        return connection;
    }

    private void requireSupported(String provider) {
        if (!providers.isSupported(provider)) {
            throw new ResourceNotFoundException("Unknown provider: " + provider);
        }
    }

    private void requireEnabled(String provider) {
        requireSupported(provider);
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
