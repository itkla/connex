package ooo.klae.connex.backend.ai.egress;

import java.util.concurrent.TimeUnit;

/** Monotonic absolute deadline shared across every network step of one AI provider call. */
public final class AiRequestDeadline {
    private final long deadlineNanos;

    private AiRequestDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static AiRequestDeadline afterMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalStateException("AI request timeout must be positive");
        }
        return new AiRequestDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    public long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    public boolean isExpired() {
        return remainingNanos() <= 0;
    }
}
