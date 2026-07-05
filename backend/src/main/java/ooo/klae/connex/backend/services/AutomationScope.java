package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;

/**
 * Thread-scoped marker for "we are currently executing a rule action". While active,
 * {@link RuleTriggerPublisher} suppresses new trigger events, so a rule's own mutations cannot
 * re-trigger rules — the guard against self-referential and cascading automation loops (e.g. a
 * {@code change_stage} action on a {@code deal.stage_changed} rule). Automation actions therefore
 * never chain into further rules; this is deliberate.
 */
@Component
public class AutomationScope {

    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Whether the current thread is inside a rule action execution. */
    public boolean isActive() {
        return active.get();
    }

    /** Marks the current thread as executing a rule action; returns the previous state to restore. */
    public boolean enter() {
        boolean previous = active.get();
        active.set(Boolean.TRUE);
        return previous;
    }

    /** Restores the marker to a state previously returned by {@link #enter()}. */
    public void restore(boolean previous) {
        if (previous) {
            active.set(Boolean.TRUE);
        } else {
            active.remove();
        }
    }
}
