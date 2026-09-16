package ooo.klae.connex.backend.signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/** Bounded per-replica fixed-window throttle for public document-acceptance links. */
@Component
public class DocumentAcceptanceRateLimiter {
    private static final int EVICTION_BATCH_SIZE = 32;

    private final SignatureProperties properties;
    private final Clock clock;
    private final LinkedHashMap<String, TokenWindow> tokenWindows = new LinkedHashMap<>();
    private final LinkedHashMap<String, Window> sourceWindows = new LinkedHashMap<>();
    private final Map<String, Integer> sourceTokenKeys = new HashMap<>();

    public DocumentAcceptanceRateLimiter(SignatureProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Consumes the source allowance before touching token buckets. Each source may own at most
     * half the token registry (at least one key), even across source-window resets. Admission,
     * allocation and expiry share one lock so concurrent requests cannot exceed either cap.
     */
    public synchronized void acquire(String tokenHash, String sourceAddress) {
        long now = clock.millis();
        long windowMillis = properties.getRateLimitWindow().toMillis();
        requireValidConfiguration(windowMillis);
        String sourceKey = sha256(sourceAddress == null
            ? "unresolved"
            : sourceAddress.trim().toLowerCase(Locale.ROOT));
        if (!acquireSource(sourceKey, now, windowMillis)
                || !acquireToken(tokenHash, sourceKey, now, windowMillis)) {
            throw new TooManyRequestsException("Too many document-link requests. Please try again later.");
        }
    }

    /** Removes at most 32 expired buckets per namespace without scanning live entries. */
    @Scheduled(fixedDelayString = "${connex.signature.rate-limit-eviction-delay-ms:60000}")
    public synchronized void evictStale() {
        long now = clock.millis();
        long windowMillis = properties.getRateLimitWindow().toMillis();
        requireValidConfiguration(windowMillis);
        evictTokens(now, windowMillis);
        evictSources(now, windowMillis);
    }

    private boolean acquireToken(String tokenHash, String sourceKey, long now, long windowMillis) {
        TokenWindow existing = tokenWindows.get(tokenHash);
        if (existing != null && elapsed(now, existing.window().startedAtMillis()) >= windowMillis) {
            tokenWindows.remove(tokenHash);
            releaseTokenKey(existing.sourceKey());
            existing = null;
        }
        if (existing == null) {
            evictTokens(now, windowMillis);
            int sourceKeys = sourceTokenKeys.getOrDefault(sourceKey, 0);
            int maxSourceKeys = Math.max(1, properties.getRateLimitMaxKeys() / 2);
            if (tokenWindows.size() >= properties.getRateLimitMaxKeys() || sourceKeys >= maxSourceKeys) {
                return false;
            }
            tokenWindows.put(tokenHash, new TokenWindow(new Window(now, 1), sourceKey));
            sourceTokenKeys.put(sourceKey, sourceKeys + 1);
            return true;
        }
        Window window = existing.window();
        if (window.count() >= properties.getMaxRequestsPerToken()) {
            return false;
        }
        tokenWindows.put(tokenHash, new TokenWindow(
            new Window(window.startedAtMillis(), window.count() + 1), existing.sourceKey()));
        return true;
    }

    private boolean acquireSource(String sourceKey, long now, long windowMillis) {
        Window existing = sourceWindows.get(sourceKey);
        if (existing != null && elapsed(now, existing.startedAtMillis()) >= windowMillis) {
            sourceWindows.remove(sourceKey);
            existing = null;
        }
        if (existing == null) {
            evictSources(now, windowMillis);
            if (sourceWindows.size() >= properties.getRateLimitMaxKeys()) {
                return false;
            }
            sourceWindows.put(sourceKey, new Window(now, 1));
            return true;
        }
        if (existing.count() >= properties.getMaxRequestsPerSource()) {
            return false;
        }
        sourceWindows.put(sourceKey, new Window(existing.startedAtMillis(), existing.count() + 1));
        return true;
    }

    private void requireValidConfiguration(long windowMillis) {
        if (windowMillis <= 0
                || properties.getMaxRequestsPerToken() <= 0
                || properties.getMaxRequestsPerSource() <= 0
                || properties.getRateLimitMaxKeys() <= 0) {
            throw new IllegalStateException("Document-acceptance rate limits must be positive");
        }
    }

    private void evictTokens(long now, long windowMillis) {
        for (int evicted = 0; evicted < EVICTION_BATCH_SIZE; evicted++) {
            Map.Entry<String, TokenWindow> oldest = tokenWindows.firstEntry();
            if (oldest == null || elapsed(now, oldest.getValue().window().startedAtMillis()) < windowMillis) {
                return;
            }
            tokenWindows.pollFirstEntry();
            releaseTokenKey(oldest.getValue().sourceKey());
        }
    }

    private void releaseTokenKey(String sourceKey) {
        sourceTokenKeys.computeIfPresent(sourceKey, (ignored, count) -> count == 1 ? null : count - 1);
    }

    private void evictSources(long now, long windowMillis) {
        for (int evicted = 0; evicted < EVICTION_BATCH_SIZE; evicted++) {
            Map.Entry<String, Window> oldest = sourceWindows.firstEntry();
            if (oldest == null || elapsed(now, oldest.getValue().startedAtMillis()) < windowMillis) {
                return;
            }
            sourceWindows.pollFirstEntry();
        }
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Window(long startedAtMillis, int count) {
    }

    private record TokenWindow(Window window, String sourceKey) {
    }
}
