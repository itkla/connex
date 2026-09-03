package ooo.klae.connex.backend.signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/** Bounded per-replica fixed-window throttle for public document-acceptance links. */
@Component
public class DocumentAcceptanceRateLimiter {
    private final SignatureProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> tokenWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> sourceWindows = new ConcurrentHashMap<>();

    public DocumentAcceptanceRateLimiter(SignatureProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Consumes one allowance in both the bearer-token and source-address namespaces. */
    public void acquire(String tokenHash, String sourceAddress) {
        long now = clock.millis();
        long windowMillis = properties.getRateLimitWindow().toMillis();
        requireValidConfiguration(windowMillis);
        boolean tokenAccepted = acquire(
            tokenWindows, tokenHash, properties.getMaxRequestsPerToken(), now, windowMillis);
        boolean sourceAccepted = acquireSource(sourceAddress, now, windowMillis);
        if (!tokenAccepted || !sourceAccepted) {
            throw new TooManyRequestsException("Too many document-link requests. Please try again later.");
        }
    }

    /** Removes expired buckets so attacker-selected invalid tokens cannot grow memory without bound. */
    @Scheduled(fixedDelayString = "${connex.signature.rate-limit-eviction-delay-ms:60000}")
    public void evictStale() {
        long now = clock.millis();
        long windowMillis = properties.getRateLimitWindow().toMillis();
        evict(tokenWindows, now, windowMillis);
        evict(sourceWindows, now, windowMillis);
    }

    private boolean acquire(
            ConcurrentHashMap<String, Window> windows,
            String key,
            int limit,
            long now,
            long windowMillis) {
        if (!windows.containsKey(key) && windows.size() >= properties.getRateLimitMaxKeys()) {
            evict(windows, now, windowMillis);
            if (windows.size() >= properties.getRateLimitMaxKeys()) {
                return false;
            }
        }
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (ignored, existing) -> {
            if (existing == null || elapsed(now, existing.startedAtMillis()) >= windowMillis) {
                accepted.set(true);
                return new Window(now, 1);
            }
            if (existing.count() >= limit) {
                return existing;
            }
            accepted.set(true);
            return new Window(existing.startedAtMillis(), existing.count() + 1);
        });
        return accepted.get();
    }

    private boolean acquireSource(String sourceAddress, long now, long windowMillis) {
        String sourceKey = sha256(sourceAddress == null
            ? "unresolved"
            : sourceAddress.trim().toLowerCase(Locale.ROOT));
        return acquire(
            sourceWindows, sourceKey, properties.getMaxRequestsPerSource(), now, windowMillis);
    }

    private void requireValidConfiguration(long windowMillis) {
        if (windowMillis <= 0
                || properties.getMaxRequestsPerToken() <= 0
                || properties.getMaxRequestsPerSource() <= 0
                || properties.getRateLimitMaxKeys() <= 0) {
            throw new IllegalStateException("Document-acceptance rate limits must be positive");
        }
    }

    private static void evict(
            ConcurrentHashMap<String, Window> windows, long now, long windowMillis) {
        windows.forEach((key, window) -> {
            if (elapsed(now, window.startedAtMillis()) >= windowMillis) {
                windows.remove(key, window);
            }
        });
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
}
