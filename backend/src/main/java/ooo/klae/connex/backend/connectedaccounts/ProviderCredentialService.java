package ooo.klae.connex.backend.connectedaccounts;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;

/**
 * Generation-safe access-token loading and refresh without provider I/O in a transaction.
 */
@Service
@RequiredArgsConstructor
public class ProviderCredentialService {
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);
    private static final Duration REFRESH_LEASE = Duration.ofMinutes(2);

    private final ProviderConnectionMapper connectionMapper;
    private final ConnectedAccountProviders providers;
    private final UserProviderSecretCipher secretCipher;
    private final ProviderTokenClient tokenClient;
    private final ProviderCredentialPersistence persistence;
    private final ObjectMapper objectMapper;

    /**
     * Returns a current access token or refreshes it under a generation-bound lease.
     */
    public String accessToken(ProviderConnection connection) {
        TokenBundle bundle = readBundle(connection);
        if (bundle.accessToken() != null
                && bundle.expiresAt() != null
                && bundle.expiresAt().isAfter(Instant.now().plus(EXPIRY_MARGIN))) {
            return bundle.accessToken();
        }
        String owner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int claimed = connectionMapper.claimRefreshLease(
            connection.getId(),
            connection.getCredentialGeneration(),
            owner,
            mysql(now),
            mysql(now.plus(REFRESH_LEASE)));
        if (claimed != 1) {
            ProviderConnection latest = connectionMapper.getById(connection.getId());
            if (latest == null
                    || latest.getCredentialGeneration() != connection.getCredentialGeneration()
                    || !"connected".equals(latest.getStatus())) {
                throw new ProviderTokenException(
                    "refresh_superseded", true,
                    "Provider connection changed before refresh");
            }
            TokenBundle latestBundle = readBundle(latest);
            if (latestBundle.accessToken() != null
                    && latestBundle.expiresAt() != null
                    && latestBundle.expiresAt().isAfter(Instant.now().plus(EXPIRY_MARGIN))) {
                return latestBundle.accessToken();
            }
            throw new ProviderTokenException(
                "refresh_in_progress", true,
                "Another worker is refreshing this provider connection");
        }
        try {
            ProviderConnection claimedConnection =
                connectionMapper.getById(connection.getId());
            if (claimedConnection == null
                    || claimedConnection.getCredentialGeneration()
                        != connection.getCredentialGeneration()
                    || !"connected".equals(claimedConnection.getStatus())) {
                throw new ProviderTokenException(
                    "refresh_superseded", true,
                    "Provider connection changed before refresh");
            }
            bundle = readBundle(claimedConnection);
            if (bundle.accessToken() != null
                    && bundle.expiresAt() != null
                    && bundle.expiresAt().isAfter(Instant.now().plus(EXPIRY_MARGIN))) {
                if (connectionMapper.releaseRefreshLease(
                        claimedConnection.getId(),
                        claimedConnection.getCredentialGeneration(),
                        owner,
                        null) != 1) {
                    throw new ProviderTokenException(
                        "refresh_superseded", true,
                        "Provider connection changed before refresh release");
                }
                return bundle.accessToken();
            }
            ProviderTokenResponse tokens = tokenClient.exchange(
                providers.tokenUri(claimedConnection.getProvider()),
                refreshForm(claimedConnection.getProvider(), bundle.refreshToken()));
            return persistence.completeRefresh(
                claimedConnection.getId(),
                claimedConnection.getCredentialGeneration(),
                owner,
                tokens,
                bundle.refreshToken());
        } catch (RuntimeException exception) {
            String code = exception instanceof ProviderTokenException tokenException
                ? tokenException.getCode()
                : "refresh_failed";
            if (exception instanceof ProviderTokenException tokenException
                    && tokenException.isRetryable()) {
                connectionMapper.releaseRefreshLease(
                    connection.getId(),
                    connection.getCredentialGeneration(),
                    owner,
                    code);
            } else {
                connectionMapper.failRefresh(
                    connection.getId(),
                    connection.getCredentialGeneration(),
                    owner,
                    code);
            }
            throw exception;
        }
    }

    private TokenBundle readBundle(ProviderConnection connection) {
        if (connection.getCredentialRef() == null) {
            throw new ProviderTokenException(
                "credential_missing", "Provider connection has no credential reference");
        }
        JsonNode bundle = objectMapper.readTree(secretCipher.decryptTokenBundle(
            connection.getProvider(),
            connection.getUserId(),
            connection.getCredentialRef()));
        String refreshToken = text(bundle, "refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ProviderTokenException(
                "refresh_token_missing", "Provider credential has no refresh token");
        }
        return new TokenBundle(
            text(bundle, "accessToken"),
            refreshToken,
            instant(text(bundle, "accessTokenExpiresAt")));
    }

    private Map<String, String> refreshForm(String provider, String refreshToken) {
        ConnectedAccountProperties.Provider client = providers.client(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", client.getClientId());
        form.put("client_secret", client.getClientSecret());
        if (ConnectedAccountProviders.MICROSOFT.equals(provider)) {
            form.put("scope", providers.scopes(provider));
        }
        return form;
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private record TokenBundle(String accessToken, String refreshToken, Instant expiresAt) {
    }
}
