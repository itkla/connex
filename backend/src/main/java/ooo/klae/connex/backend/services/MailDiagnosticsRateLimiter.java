package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window throttle for diagnostic test sends, keyed by actor and workspace.
 *
 * <p>The diagnostic send is synchronous and, under managed mail, goes through the deployment's
 * shared relay. Without a bound, any administrator holding {@code WORKSPACE_SETTINGS} and a recent
 * session could consume the instance's mail quota and hold request threads by issuing the call in
 * parallel. Keying on actor and workspace together means one busy workspace cannot exhaust another
 * administrator's allowance.
 *
 * <p>Single-JVM only, matching {@link PasswordResetRateLimiter} and the in-memory session model; a
 * multi-instance deployment needs a shared store for this to be authoritative.
 */
@Component
public class MailDiagnosticsRateLimiter {

    private final int maxPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public MailDiagnosticsRateLimiter(
            @Value("${connex.mail.diagnostics.max-test-sends:3}") int maxPerWindow,
            @Value("${connex.mail.diagnostics.test-send-window-seconds:300}") long windowSeconds,
            Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowSeconds * 1000L;
        this.clock = clock;
    }

    /**
     * Records a test-send attempt and reports whether it is within the allowed window.
     *
     * @param workspaceId workspace whose transport is being tested
     * @param actorId requesting administrator
     * @param nowMillis current epoch time in milliseconds
     * @return true when the attempt is under the cap
     */
    public boolean tryAcquire(int workspaceId, int actorId, long nowMillis) {
        String key = workspaceId + ":" + actorId;
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || nowMillis - existing.start >= windowMillis) {
                return new Window(nowMillis, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= maxPerWindow;
    }

    /**
     * Drops windows whose period has elapsed, bounding memory growth.
     *
     * <p>Nothing else prunes the map: an elapsed window is only replaced when that same actor tests
     * the same workspace again, so without this sweep the map retains one entry for every
     * administrator and workspace pair that has ever run a test send.
     */
    @Scheduled(fixedDelayString = "${connex.mail.diagnostics.eviction-delay-ms:300000}")
    public void evictStale() {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMillis);
    }

    int trackedWindows() {
        return windows.size();
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
