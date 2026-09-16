package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window throttle for administrator-created accounts, keyed by the creating actor.
 *
 * <p>Under registration verification, {@code POST /api/users} sends a verification link to the
 * caller-supplied address. Without a bound, any holder of {@code MEMBER_MANAGE} and a recent
 * session could emit unlimited instance-branded mail to addresses of their choosing, each carrying
 * a display name they also chose. Keying on the actor keeps one administrator's burst from
 * consuming another's allowance, and the actor is already recorded on the {@code auth.register}
 * audit entry, so a throttled actor is attributable.
 *
 * <p>Single-JVM only, matching {@link MailDiagnosticsRateLimiter} and the in-memory session model;
 * a multi-instance deployment needs a shared store for this to be authoritative.
 */
@Component
public class AccountCreationRateLimiter {

    private final int maxPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Window> windows = new ConcurrentHashMap<>();

    public AccountCreationRateLimiter(
            @Value("${connex.account-creation.max-per-actor:20}") int maxPerWindow,
            @Value("${connex.account-creation.window-seconds:900}") long windowSeconds,
            Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowSeconds * 1000L;
        this.clock = clock;
    }

    /**
     * Records a creation attempt by the actor and reports whether it is within the allowed window.
     *
     * @param actorId the administrator creating the account
     * @return true when the attempt is under the cap
     */
    public boolean tryAcquire(int actorId) {
        long nowMillis = clock.millis();
        Window window = windows.compute(actorId, (ignored, existing) -> {
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
     * <p>Nothing else prunes the map: an elapsed window is only replaced when that same actor
     * creates another account, so without this sweep the map retains one entry for every
     * administrator that has ever created one.
     */
    @Scheduled(fixedDelayString = "${connex.account-creation.eviction-delay-ms:900000}")
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
