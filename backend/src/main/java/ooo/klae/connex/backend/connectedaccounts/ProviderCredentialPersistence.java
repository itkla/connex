package ooo.klae.connex.backend.connectedaccounts;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Transactional credential mutation boundary with a stable user-root lock order.
 */
@Component
@RequiredArgsConstructor
public class ProviderCredentialPersistence {
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final UserMapper userMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final UserProviderSecretCipher secretCipher;
    private final ObjectMapper objectMapper;

    /** Captures the row boundary that one new authorization must not outlive. */
    @Transactional
    public ProviderConnectionExpectation authorizationExpectation(
            int userId, String provider) {
        requireUser(userId);
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForShare(userId, provider);
        if (connection != null
                && ("revoking".equals(connection.getStatus())
                    || "disconnecting".equals(connection.getStatus())
                    || "purge_failed".equals(connection.getStatus()))) {
            throw new ConflictException(
                "Provider disconnect cleanup must finish before reconnecting");
        }
        return ProviderConnectionExpectation.snapshot(connection);
    }

    /** Rejects an authorization whose captured row boundary has already changed. */
    @Transactional
    public void requireAuthorizationExpectation(
            int userId,
            String provider,
            ProviderConnectionExpectation expectation) {
        requireUser(userId);
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForShare(userId, provider);
        if (!expectation.matches(connection)) {
            throw new ConflictException(
                "Provider authorization was superseded by a connection change");
        }
    }

    /** Stores a first connection or atomically replaces an expected reconnect generation. */
    @Transactional
    public boolean storeConnection(
            int userId,
            String provider,
            ProviderConnectionExpectation expectation,
            ProviderTokenResponse tokens,
            String accountId,
            String accountEmail,
            String grantedScopes) {
        requireUser(userId);
        ProviderConnection existing =
            connectionMapper.getByUserAndProviderForUpdate(userId, provider);
        if (!expectation.matches(existing)) {
            throw new ConflictException(
                "Provider authorization was superseded by a connection change");
        }
        if (existing != null && existing.getProviderAccountId() == null) {
            throw new ProviderRetainedDataResetRequiredException();
        }
        if (existing != null
                && ("disconnecting".equals(existing.getStatus())
                    || "purge_failed".equals(existing.getStatus())
                    || "revoking".equals(existing.getStatus()))) {
            throw new ConflictException(
                "Provider disconnect cleanup must finish before reconnecting");
        }
        if (existing != null
                && existing.getProviderAccountId() != null
                && !existing.getProviderAccountId().equals(accountId)) {
            throw new ProviderRetainedDataResetRequiredException();
        }
        String reference = secretCipher.encryptTokenBundle(
            provider, userId, bundleJson(tokens, null));
        ProviderConnection connection = existing == null ? new ProviderConnection() : existing;
        connection.setUserId(userId);
        connection.setProvider(provider);
        connection.setStatus("connected");
        connection.setProviderAccountEmail(accountEmail);
        connection.setProviderAccountId(accountId);
        connection.setGrantedScopes(grantedScopes);
        connection.setCredentialRef(reference);
        connection.setCredentialGeneration(existing == null
            ? 1
            : existing.getCredentialGeneration() + 1);
        connection.setAccessTokenExpiresAt(expiresAt(tokens));
        connection.setRefreshLeaseOwner(null);
        connection.setRefreshLeaseUntil(null);
        connection.setDisconnectingAt(null);
        connection.setDisconnectAttemptAt(null);
        connection.setCaptureReconcileRequired(true);
        connection.setCaptureReconcileAfterWorkspaceId(0);
        connection.setCaptureReconcileLeaseOwner(null);
        connection.setCaptureReconcileLeaseUntil(null);
        connection.setCaptureReconcileNextAttemptAt(null);
        connection.setCaptureReconcileFailures(0);
        connection.setErrorCode(null);
        if (existing == null) {
            connectionMapper.insert(connection);
        } else {
            connectionMapper.update(connection);
        }
        return existing == null;
    }

    /**
     * Commits a refresh only when the same generation and lease still own the connection.
     */
    @Transactional
    public String completeRefresh(
            int connectionId,
            long generation,
            String leaseOwner,
            ProviderTokenResponse tokens,
            String retainedRefreshToken) {
        ProviderConnection snapshot = connectionMapper.getById(connectionId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Provider connection no longer exists");
        }
        requireUser(snapshot.getUserId());
        ProviderConnection locked = connectionMapper.getByIdForUpdate(connectionId);
        if (locked == null
                || locked.getCredentialGeneration() != generation
                || !leaseOwner.equals(locked.getRefreshLeaseOwner())
                || !"connected".equals(locked.getStatus())) {
            throw new ProviderTokenException(
                "refresh_superseded", true,
                "Credential generation changed during refresh");
        }
        String refreshToken = tokens.refreshToken() == null
            ? retainedRefreshToken
            : tokens.refreshToken();
        String reference = secretCipher.encryptTokenBundle(
            locked.getProvider(),
            locked.getUserId(),
            bundleJson(tokens, refreshToken));
        String expiresAt = expiresAt(tokens);
        if (connectionMapper.completeRefresh(
                connectionId, generation, leaseOwner, reference, expiresAt) != 1) {
            throw new ProviderTokenException(
                "refresh_superseded", true,
                "Credential generation changed during refresh");
        }
        return tokens.accessToken();
    }

    private void requireUser(int userId) {
        if (userMapper.lockByIdForShare(userId) == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        if (userMapper.isAccountDeletionReserved(userId)) {
            throw new ConflictException("Account deletion is in progress");
        }
    }

    private String bundleJson(ProviderTokenResponse tokens, String refreshTokenOverride) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("refreshToken",
            refreshTokenOverride == null ? tokens.refreshToken() : refreshTokenOverride);
        bundle.put("accessToken", tokens.accessToken());
        if (tokens.expiresIn() != null) {
            bundle.put("accessTokenExpiresAt",
                Instant.now().plusSeconds(tokens.expiresIn()).toString());
        }
        if (tokens.scope() != null) {
            bundle.put("scope", tokens.scope());
        }
        bundle.put("obtainedAt", Instant.now().toString());
        return objectMapper.writeValueAsString(bundle);
    }

    private static String expiresAt(ProviderTokenResponse tokens) {
        return tokens.expiresIn() == null
            ? null
            : LocalDateTime.ofInstant(
                Instant.now().plusSeconds(tokens.expiresIn()),
                ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }
}
