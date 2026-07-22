package ooo.klae.connex.backend.ai.provider.vertex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exchanges Google service-account JWT assertions for short-lived OAuth access tokens. Token
 * requests always use Google's fixed token endpoint through bounded, validated, pinned DNS and
 * cache tokens by a credential digest.
 */
@Component
public class GoogleAccessTokenClient {
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final String TOKEN_HOST = "oauth2.googleapis.com";
    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";
    private static final Duration JWT_LIFETIME = Duration.ofHours(1);
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final int BUFFER_BYTES = 8192;

    private final RestClient restClient;
    private final FixedAiProviderClient providerClient;
    private final int maxResponseBytes;
    private final long requestTimeoutMillis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedAccessToken> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CachedAccessToken>> refreshes =
            new ConcurrentHashMap<>();

    @Autowired
    public GoogleAccessTokenClient(
            AiProperties aiProperties,
            FixedAiProviderClient providerClient,
            ObjectMapper objectMapper,
            Clock clock) {
        Objects.requireNonNull(aiProperties, "aiProperties");
        this.restClient = null;
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
        this.maxResponseBytes = positiveInt(aiProperties.getMaxResponseBytes(), "max response bytes");
        this.requestTimeoutMillis = positiveLong(aiProperties.getRequestTimeoutMs(), "request timeout");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    GoogleAccessTokenClient(RestClient restClient, int maxResponseBytes, ObjectMapper objectMapper, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.providerClient = null;
        this.maxResponseBytes = positiveInt(maxResponseBytes, "max response bytes");
        this.requestTimeoutMillis = Duration.ofSeconds(60).toMillis();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns a fresh access token for the supplied Google service-account credential.
     * @param credentials decrypted Vertex service-account credential bundle
     * @return OAuth access token
     */
    public String accessToken(AiCredentials credentials) {
        return accessToken(credentials, AiRequestDeadline.afterMillis(requestTimeoutMillis));
    }

    String accessToken(AiCredentials credentials, AiRequestDeadline deadline) {
        if (credentials == null) {
            throw new AiProviderException("Vertex credentials are required");
        }
        requireDeadline(deadline);
        ServiceAccount serviceAccount = parseServiceAccount(credentials.require("serviceAccountJson"));
        String cacheKey = credentialDigest(serviceAccount);
        requireDeadline(deadline);
        CachedAccessToken cached = cache.get(cacheKey);
        Instant now = Instant.now(clock);
        if (isFresh(cached, now)) {
            return cached.value();
        }
        CompletableFuture<CachedAccessToken> refresh = new CompletableFuture<>();
        CompletableFuture<CachedAccessToken> current = refreshes.putIfAbsent(cacheKey, refresh);
        if (current != null) {
            CachedAccessToken resolved = awaitRefresh(current, deadline);
            requireDeadline(deadline);
            return resolved.value();
        }
        CachedAccessToken resolved;
        try {
            CachedAccessToken latest = cache.get(cacheKey);
            Instant refreshTime = Instant.now(clock);
            CachedAccessToken refreshed = isFresh(latest, refreshTime)
                    ? latest
                    : exchange(serviceAccount, refreshTime, deadline);
            requireDeadline(deadline);
            cache.put(cacheKey, refreshed);
            refresh.complete(refreshed);
            resolved = refreshed;
        } catch (RuntimeException exception) {
            refresh.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshes.remove(cacheKey, refresh);
        }
        requireDeadline(deadline);
        evictIfOversized(cacheKey);
        requireDeadline(deadline);
        return resolved.value();
    }

    private CachedAccessToken awaitRefresh(
            CompletableFuture<CachedAccessToken> refresh,
            AiRequestDeadline deadline) {
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            throw deadlineExceeded();
        }
        try {
            return refresh.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw deadlineExceeded();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Vertex invocation was interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiProviderException providerException) {
                throw providerException;
            }
            throw new AiProviderException("Google OAuth token exchange failed during transport");
        }
    }

    private static boolean isFresh(CachedAccessToken cached, Instant now) {
        return cached != null && now.isBefore(cached.expiresAt().minus(REFRESH_SKEW));
    }

    private CachedAccessToken exchange(
            ServiceAccount serviceAccount,
            Instant issuedAt,
            AiRequestDeadline deadline) {
        String assertion = buildAssertion(serviceAccount, issuedAt);
        String formBody = "grant_type=" + formEncode(JWT_BEARER_GRANT)
                + "&assertion=" + formEncode(assertion);
        TokenResponse response;
        try {
            response = sendOnce(formBody.getBytes(StandardCharsets.UTF_8), deadline);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiProviderException("Google OAuth token exchange failed during transport");
        } catch (RuntimeException exception) {
            throw new AiProviderException("Google OAuth token exchange failed during transport");
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new AiProviderException("Google OAuth token exchange failed with status " + response.statusCode());
        }
        return parseTokenResponse(response.body());
    }

    private TokenResponse sendOnce(byte[] body, AiRequestDeadline deadline) {
        if (providerClient != null) {
            FixedAiProviderClient.Response response = providerClient.post(
                    TOKEN_ENDPOINT,
                    Set.of(TOKEN_HOST),
                    Map.of(
                            "Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType(),
                            "Accept", ContentType.APPLICATION_JSON.getMimeType()),
                    ContentType.APPLICATION_FORM_URLENCODED,
                    body,
                    deadline,
                    "Google OAuth token exchange");
            return new TokenResponse(response.statusCode(), response.body());
        }
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON);
        AiEgressGuard.requireFetchableHost(TOKEN_HOST, false);
        return spec.body(body)
                .exchange((request, response) -> new TokenResponse(response.getStatusCode().value(),
                        readBounded(response.getBody())));
    }

    private CachedAccessToken parseTokenResponse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw invalidTokenResponse();
            }
            JsonNode accessTokenNode = root.path("access_token");
            JsonNode expiresInNode = root.path("expires_in");
            if (!accessTokenNode.isString() || !expiresInNode.isIntegralNumber()
                    || !expiresInNode.canConvertToLong()) {
                throw invalidTokenResponse();
            }
            String accessToken = accessTokenNode.asString();
            long expiresIn = expiresInNode.longValue();
            if (accessToken.isBlank() || accessToken.indexOf('\r') >= 0 || accessToken.indexOf('\n') >= 0
                    || expiresIn <= 0) {
                throw invalidTokenResponse();
            }
            return new CachedAccessToken(accessToken, Instant.now(clock).plusSeconds(expiresIn));
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidTokenResponse();
        }
    }

    private ServiceAccount parseServiceAccount(String serviceAccountJson) {
        try {
            JsonNode root = objectMapper.readTree(serviceAccountJson);
            if (root == null || !root.isObject()
                    || !root.path("client_email").isString()
                    || !root.path("private_key").isString()) {
                throw invalidServiceAccount();
            }
            String clientEmail = root.path("client_email").asString().trim();
            String privateKey = root.path("private_key").asString();
            if (clientEmail.isBlank() || privateKey.isBlank()) {
                throw invalidServiceAccount();
            }
            return new ServiceAccount(clientEmail, privateKey);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidServiceAccount();
        }
    }

    private String buildAssertion(ServiceAccount serviceAccount, Instant issuedAt) {
        try {
            ObjectNode header = objectMapper.createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            ObjectNode claims = objectMapper.createObjectNode();
            claims.put("iss", serviceAccount.clientEmail());
            claims.put("scope", CLOUD_PLATFORM_SCOPE);
            claims.put("aud", TOKEN_ENDPOINT.toString());
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("exp", issuedAt.plus(JWT_LIFETIME).getEpochSecond());
            String encodedHeader = base64Url(objectMapper.writeValueAsBytes(header));
            String encodedClaims = base64Url(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(serviceAccount.privateKeyPem()));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Vertex service-account assertion could not be created");
        }
    }

    private static PrivateKey privateKey(String privateKeyPem) {
        try {
            String normalized = privateKeyPem.trim();
            if (!normalized.startsWith(PRIVATE_KEY_BEGIN) || !normalized.endsWith(PRIVATE_KEY_END)) {
                throw invalidServiceAccount();
            }
            String encoded = normalized.substring(PRIVATE_KEY_BEGIN.length(),
                    normalized.length() - PRIVATE_KEY_END.length()).replaceAll("\\s", "");
            if (encoded.isEmpty()) {
                throw invalidServiceAccount();
            }
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidServiceAccount();
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxResponseBytes) {
                throw new AiProviderException("Google OAuth token response exceeded the configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private void evictIfOversized(String retainedCacheKey) {
        int entriesToRemove = Math.max(0, cache.size() - MAX_CACHE_ENTRIES);
        int inspected = 0;
        for (Map.Entry<String, CachedAccessToken> entry : cache.entrySet()) {
            if (entriesToRemove == 0 || inspected >= MAX_CACHE_ENTRIES) {
                return;
            }
            inspected++;
            if (!entry.getKey().equals(retainedCacheKey)
                    && cache.remove(entry.getKey(), entry.getValue())) {
                entriesToRemove--;
            }
        }
    }

    private static String credentialDigest(ServiceAccount serviceAccount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serviceAccount.clientEmail().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(serviceAccount.privateKeyPem().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int positiveInt(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static long positiveLong(long value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static void requireDeadline(AiRequestDeadline deadline) {
        if (deadline == null || deadline.isExpired()) {
            throw deadlineExceeded();
        }
    }

    private static AiProviderException deadlineExceeded() {
        return new AiProviderException("Vertex invocation exceeded its deadline");
    }

    private static AiProviderException invalidServiceAccount() {
        return new AiProviderException("Vertex service-account credential was invalid");
    }

    private static AiProviderException invalidTokenResponse() {
        return new AiProviderException("Google OAuth token response was invalid");
    }

    @Override
    public String toString() {
        return "GoogleAccessTokenClient[redacted]";
    }

    private record ServiceAccount(String clientEmail, String privateKeyPem) {
        @Override
        public String toString() {
            return "ServiceAccount[redacted]";
        }
    }

    private record CachedAccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedAccessToken[redacted]";
        }
    }

    private record TokenResponse(int statusCode, byte[] body) {
        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }
}
