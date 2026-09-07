package ooo.klae.connex.backend.publicapi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.config.AuditIntegrityProperties;

/** Per-JVM fixed-window request limiter for pre-authentication and credential traffic. */
@Component
public class ApiRateLimiter {
    private static final int DEFAULT_MAX_PRE_AUTH_WINDOWS = 100_000;
    private static final int DEFAULT_NEW_TOKEN_KEYS_PER_CLIENT = 60;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] TOKEN_BUCKET_DOMAIN =
        "connex.public-api.pre-auth-rate-limit.v1\0".getBytes(StandardCharsets.UTF_8);
    private static final int TOKEN_BUCKET_KEY_BYTES = 16;

    private final int credentialLimit;
    private final int clientLimit;
    private final int tokenLimit;
    private final long windowSeconds;
    private final Clock clock;
    private final byte[] hmacKey;
    private final int maxClientWindows;
    private final int maxTokenWindows;
    private final int newTokenKeysPerClient;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> clientWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> tokenWindows = new ConcurrentHashMap<>();
    private final Object preAuthLock = new Object();
    private long lastClientEvictionSecond = Long.MIN_VALUE;
    private long lastTokenEvictionSecond = Long.MIN_VALUE;
    private long clientEvictionScanCount;
    private long tokenEvictionScanCount;

    /** Creates the configured per-credential limiter. */
    @Autowired
    public ApiRateLimiter(
            @Value("${connex.public-api.rate-limit.requests:600}") int credentialLimit,
            @Value("${connex.public-api.rate-limit.pre-auth-client-requests:1200}") int clientLimit,
            @Value("${connex.public-api.rate-limit.pre-auth-token-requests:600}") int tokenLimit,
            @Value("${connex.public-api.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${connex.public-api.rate-limit.max-client-windows:100000}")
            int maxClientWindows,
            @Value("${connex.public-api.rate-limit.max-token-windows:100000}")
            int maxTokenWindows,
            @Value("${connex.public-api.rate-limit.pre-auth-new-token-keys-per-client:60}")
            int newTokenKeysPerClient,
            AuditIntegrityProperties auditIntegrityProperties) {
        this(
            credentialLimit,
            clientLimit,
            tokenLimit,
            windowSeconds,
            Clock.systemUTC(),
            auditIntegrityProperties.hmacSecretBytes(),
            maxClientWindows,
            maxTokenWindows,
            newTokenKeysPerClient);
    }

    ApiRateLimiter(int limit, long windowSeconds, Clock clock) {
        this(
            limit,
            limit,
            limit,
            windowSeconds,
            clock,
            "public-api-rate-limiter-test-key".getBytes(StandardCharsets.UTF_8),
            DEFAULT_MAX_PRE_AUTH_WINDOWS,
            DEFAULT_MAX_PRE_AUTH_WINDOWS,
            DEFAULT_NEW_TOKEN_KEYS_PER_CLIENT);
    }

    ApiRateLimiter(
            int credentialLimit,
            int clientLimit,
            int tokenLimit,
            long windowSeconds,
            Clock clock,
            byte[] hmacKey) {
        this(
            credentialLimit,
            clientLimit,
            tokenLimit,
            windowSeconds,
            clock,
            hmacKey,
            DEFAULT_MAX_PRE_AUTH_WINDOWS,
            DEFAULT_MAX_PRE_AUTH_WINDOWS,
            DEFAULT_NEW_TOKEN_KEYS_PER_CLIENT);
    }

    ApiRateLimiter(
            int credentialLimit,
            int clientLimit,
            int tokenLimit,
            long windowSeconds,
            Clock clock,
            byte[] hmacKey,
            int maxClientWindows,
            int maxTokenWindows,
            int newTokenKeysPerClient) {
        if (credentialLimit < 1 || clientLimit < 1 || tokenLimit < 1 || windowSeconds < 1
                || maxClientWindows < 1 || maxTokenWindows < 1
                || newTokenKeysPerClient < 1) {
            throw new IllegalArgumentException("Public API rate limit and window must be positive");
        }
        if (hmacKey == null || hmacKey.length < 16) {
            throw new IllegalArgumentException("Public API rate-limit HMAC key is too short");
        }
        this.credentialLimit = credentialLimit;
        this.clientLimit = clientLimit;
        this.tokenLimit = tokenLimit;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
        this.hmacKey = hmacKey.clone();
        this.maxClientWindows = maxClientWindows;
        this.maxTokenWindows = maxTokenWindows;
        this.newTokenKeysPerClient = newTokenKeysPerClient;
    }

    /** Consumes the client-address allowance first, then a pseudonymous full-token allowance. */
    public Decision acquireBeforeAuthentication(String clientAddress, String rawToken) {
        synchronized (preAuthLock) {
            String normalizedClient = normalizeKey(clientAddress);
            String clientKey = "c:" + normalizedClient;
            Decision client = acquirePreAuthentication(
                clientWindows, clientKey, clientLimit, maxClientWindows, true);
            if (!client.allowed()) {
                return client;
            }
            if (rawToken == null) {
                return client;
            }
            String tokenKey = "t:" + tokenBucketKey(rawToken);
            if (tokenWindows.containsKey(tokenKey)) {
                return acquirePreAuthentication(
                    tokenWindows, tokenKey, tokenLimit, maxTokenWindows, false);
            }
            Window clientWindow = clientWindows.get(clientKey);
            String attributedKey = clientWindow.tokenKeysCreated() >= newTokenKeysPerClient - 1
                ? "t:" + normalizedClient + ":*"
                : tokenKey;
            boolean newAttributedKey = !tokenWindows.containsKey(attributedKey);
            Decision token = acquirePreAuthentication(
                tokenWindows, attributedKey, tokenLimit, maxTokenWindows, false);
            if (newAttributedKey && tokenWindows.containsKey(attributedKey)) {
                clientWindows.computeIfPresent(clientKey, (ignored, current) -> new Window(
                    current.resetAt(), current.count(), current.tokenKeysCreated() + 1));
            }
            return token;
        }
    }

    /** Consumes one request allowance and returns the complete response-header decision. */
    public Decision acquire(long credentialId) {
        return acquire(windows, credentialId, credentialLimit);
    }

    /** Removes expired per-credential windows to bound memory growth. */
    @Scheduled(fixedDelayString = "${connex.public-api.rate-limit.eviction-delay-ms:600000}")
    public void evictStale() {
        long now = clock.instant().getEpochSecond();
        windows.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
        clientWindows.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
        tokenWindows.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
    }

    int preAuthClientWindowCount() {
        return clientWindows.size();
    }

    int preAuthTokenWindowCount() {
        return tokenWindows.size();
    }

    long clientEvictionScanCount() {
        return clientEvictionScanCount;
    }

    long tokenEvictionScanCount() {
        return tokenEvictionScanCount;
    }

    private Decision acquirePreAuthentication(
            ConcurrentHashMap<String, Window> target,
            String key,
            int limit,
            int maxWindows,
            boolean clientNamespace) {
        long now = clock.instant().getEpochSecond();
        Window existing = target.get(key);
        if (existing == null && target.size() >= maxWindows) {
            evictExpired(target, now, clientNamespace);
            if (target.size() >= maxWindows) {
                return new Decision(false, limit, 0, now + windowSeconds, windowSeconds);
            }
        }
        return acquire(target, key, limit, now);
    }

    private void evictExpired(
            ConcurrentHashMap<String, Window> target, long now, boolean clientNamespace) {
        long lastEviction = clientNamespace
            ? lastClientEvictionSecond
            : lastTokenEvictionSecond;
        if (lastEviction == now) {
            return;
        }
        target.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
        if (clientNamespace) {
            lastClientEvictionSecond = now;
            clientEvictionScanCount++;
        } else {
            lastTokenEvictionSecond = now;
            tokenEvictionScanCount++;
        }
    }

    private <K> Decision acquire(
            ConcurrentHashMap<K, Window> target,
            K key,
            int limit) {
        return acquire(target, key, limit, clock.instant().getEpochSecond());
    }

    private <K> Decision acquire(
            ConcurrentHashMap<K, Window> target,
            K key,
            int limit,
            long now) {
        Window window = target.compute(key, (ignored, current) -> {
            if (current == null || now >= current.resetAt()) {
                return new Window(now + windowSeconds, 1, 0);
            }
            return new Window(
                current.resetAt(), current.count() + 1, current.tokenKeysCreated());
        });
        int remaining = Math.max(0, limit - window.count());
        long retryAfter = Math.max(1, window.resetAt() - now);
        return new Decision(window.count() <= limit, limit, remaining, window.resetAt(), retryAfter);
    }

    private static String normalizeKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String tokenBucketKey(String rawToken) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            mac.update(TOKEN_BUCKET_DOMAIN);
            byte[] digest = mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, TOKEN_BUCKET_KEY_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "HMAC-SHA256 is required for public API rate limiting", exception);
        }
    }

    /** Headers and admission outcome for one request. */
    public record Decision(
            boolean allowed,
            int limit,
            int remaining,
            long resetAt,
            long retryAfterSeconds) {
    }

    private record Window(long resetAt, int count, int tokenKeysCreated) {
    }
}
