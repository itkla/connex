package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window CSP report throttle keyed by resolved client address.
 *
 * <p>Unlike the authenticated client-error throttle this never throws: the report endpoint answers
 * {@code 204} for every bounded body so that a browser cannot learn anything from the response, and
 * an exhausted window simply drops the report.
 *
 * <p>The key space is unauthenticated, so a source rotating through addresses could otherwise grow
 * the window map without bound between evictions. The number of tracked addresses is therefore
 * capped — but admission stays fair: an address the throttle has never seen is <strong>never</strong>
 * refused for want of capacity. When the map is full, the least recently used window is dropped to
 * make room. Refusing newcomers instead would let one rotating source hold every slot hostage: each
 * of its requests refreshes its own window, {@link #evictStale()} then frees nothing, and the
 * collector goes blind to every other client — including the browser reporting a live XSS.
 *
 * <p>Fairness must not cost more than the request it protects. The windows are held in an
 * access-ordered {@link LinkedHashMap}, so the least recently used entry is the first one the
 * iterator yields and admitting a newcomer at the cap evicts it in constant time. Scanning every
 * tracked window to find the oldest would instead turn each request from an unseen address into a
 * pass over up to {@code max-tracked-clients} entries, which on an unauthenticated route hands a
 * rotating source that cost for free.
 *
 * <p>Access ordering is what makes recency structural rather than a field to maintain: every
 * {@code get} and {@code put} in {@link #tryAcquire(String)} promotes the address to most recently
 * used, including a request that is refused for having exhausted its window, while
 * {@code containsKey} and the iteration in {@link #evictStale()} deliberately do not.
 *
 * <p>{@link LinkedHashMap} is not thread-safe and access ordering mutates it even on reads, so a
 * single {@link ReentrantLock} guards every operation. The critical sections are a handful of
 * constant-time map operations with no I/O and no nested locking, so serialising them costs far
 * less than the parsing and logging each admitted report goes on to do. The lock buys a second
 * property the previous lock-free version could not offer: the capacity check and the insert are
 * now one atomic step, so the cap is an exact bound rather than one that concurrent first sightings
 * of distinct addresses could overshoot.
 *
 * <p>What the cap does <em>not</em> buy under saturation is per-client isolation. A client whose
 * window is evicted starts its next report on a fresh allowance, so a source rotating through more
 * than {@code max-tracked-clients} addresses can raise the throttle's effective ceiling. That is the
 * deliberate trade: bounded memory and a collector that still hears from everyone, rather than an
 * exact per-client ceiling and a collector one source can silence. Reaching the cap is logged once
 * per window at {@code WARN} so saturation is visible to operators rather than indistinguishable
 * from ordinary throttling.
 */
@Component
public class CspReportRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(CspReportRateLimiter.class);
    private static final long NEVER = Long.MIN_VALUE;
    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private final int maxReports;
    private final long windowMillis;
    private final int maxTrackedClients;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Window> windows =
            new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);
    private long saturationLoggedAtMillis = NEVER;

    public CspReportRateLimiter(
            @Value("${connex.csp-reports.max-reports-per-window:60}") int maxReports,
            @Value("${connex.csp-reports.window-seconds:60}") long windowSeconds,
            @Value("${connex.csp-reports.max-tracked-clients:10000}") int maxTrackedClients,
            Clock clock) {
        if (maxReports <= 0 || windowSeconds <= 0 || maxTrackedClients <= 0) {
            throw new IllegalArgumentException("CSP report rate-limit settings must be positive");
        }
        this.maxReports = maxReports;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.maxTrackedClients = maxTrackedClients;
        this.clock = clock;
    }

    /**
     * Consumes one report allowance for a client address.
     *
     * <p>An address that is not yet tracked is always admitted; if that would exceed the
     * tracked-address cap, the least recently used window is evicted first. Every call, admitted or
     * refused, makes the address the most recently used one.
     *
     * @param key the resolved client address
     * @return whether the client's current window still had an allowance
     */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        lock.lock();
        try {
            makeRoomFor(key, now);
            Window existing = windows.get(key);
            if (existing == null || elapsed(now, existing.startedAtMillis()) >= windowMillis) {
                windows.put(key, new Window(now, 1));
                return true;
            }
            if (existing.count() >= maxReports) {
                return false;
            }
            windows.put(key, new Window(existing.startedAtMillis(), existing.count() + 1));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops expired client windows.
     */
    @Scheduled(fixedDelayString = "${connex.csp-reports.eviction-delay-ms:300000}")
    public void evictStale() {
        long now = clock.millis();
        lock.lock();
        try {
            windows.entrySet().removeIf(entry ->
                    elapsed(now, entry.getValue().startedAtMillis()) >= windowMillis);
        } finally {
            lock.unlock();
        }
    }

    int trackedKeys() {
        lock.lock();
        try {
            return windows.size();
        } finally {
            lock.unlock();
        }
    }

    boolean tracks(String key) {
        lock.lock();
        try {
            return windows.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    private void makeRoomFor(String key, long now) {
        if (windows.size() < maxTrackedClients || windows.containsKey(key)) {
            return;
        }
        logSaturation(now);
        evictLeastRecentlyUsed();
    }

    private void evictLeastRecentlyUsed() {
        Iterator<String> addresses = windows.keySet().iterator();
        if (addresses.hasNext()) {
            addresses.next();
            addresses.remove();
        }
    }

    private void logSaturation(long now) {
        if (saturationLoggedAtMillis != NEVER
                && elapsed(now, saturationLoggedAtMillis) < windowMillis) {
            return;
        }
        saturationLoggedAtMillis = now;
        log.warn("csp.report.throttle.saturated trackedClients={}", maxTrackedClients);
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private record Window(long startedAtMillis, int count) {
    }
}
