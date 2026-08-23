package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Atomic in-memory fixed-window admission for the assistant scope preview.
 *
 * <p>A preview evaluates a whole smart segment against the workspace. It costs no model tokens, so
 * no generation budget bounds it, and the cockpit calls it while a member edits scope chips: without
 * a limit a single session can drive unbounded segment evaluation. The window is per workspace and
 * member, matching the other interactive per-principal throttles, and is deliberately generous
 * enough for chip-by-chip editing while stopping a loop.
 */
@Component
public class AiChatScopePreviewRateLimiter {
    private final int maxPreviews;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<PreviewKey, Window> windows = new ConcurrentHashMap<>();

    public AiChatScopePreviewRateLimiter(
            @Value("${connex.ai.assistant.scope-preview.max-per-window:30}") int maxPreviews,
            @Value("${connex.ai.assistant.scope-preview.window-seconds:60}") long windowSeconds,
            Clock clock) {
        if (maxPreviews <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Assistant scope preview rate-limit settings must be positive");
        }
        this.maxPreviews = maxPreviews;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.clock = clock;
    }

    /**
     * Consumes one preview allowance for the resolved principal.
     *
     * @param workspaceId active workspace
     * @param userId asking member
     * @throws TooManyRequestsException when the current window is exhausted
     */
    public void acquire(int workspaceId, int userId) {
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(new PreviewKey(workspaceId, userId), (key, existing) -> {
            if (existing == null || elapsed(now, existing.startedAtMillis()) >= windowMillis) {
                accepted.set(true);
                return new Window(now, 1);
            }
            if (existing.count() >= maxPreviews) {
                return existing;
            }
            accepted.set(true);
            return new Window(existing.startedAtMillis(), existing.count() + 1);
        });
        if (!accepted.get()) {
            throw new TooManyRequestsException(
                    "Too many scope previews. Please try again shortly.");
        }
    }

    /** Drops expired principal windows. */
    @Scheduled(fixedDelayString = "${connex.ai.assistant.scope-preview.eviction-delay-ms:300000}")
    public void evictStale() {
        long now = clock.millis();
        windows.forEach((key, window) -> {
            if (elapsed(now, window.startedAtMillis()) >= windowMillis) {
                windows.remove(key, window);
            }
        });
    }

    int trackedPrincipals() {
        return windows.size();
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private record PreviewKey(int workspaceId, int userId) {
    }

    private record Window(long startedAtMillis, int count) {
    }
}
