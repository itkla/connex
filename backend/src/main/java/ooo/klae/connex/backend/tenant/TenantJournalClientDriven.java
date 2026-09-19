package ooo.klae.connex.backend.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an HTTP handler the client calls on its own schedule — a poll, a heartbeat, a debounce, or
 * a refresh fan-out driven by a server-pushed frame — rather than on a member's action.
 *
 * <p>A successful completion of such a handler is omitted from the support journal, because its
 * volume tracks agent steps, open surfaces, and participant count instead of anything an operator
 * is diagnosing. Failures are retained by default: a failing read is the record an operator greps
 * for, and its volume is bounded by the member actions that drive the client loop.
 *
 * <p>{@link #retainFailures()} is false only where the client retries indefinitely after a failure
 * with no member action to stop it, which makes failure retention unbounded in time rather than
 * bounded by member actions.
 *
 * <p>The marker is only meaningful on a handler that is already journal-attributable through
 * {@link TenantJournalAttributable}; on any other handler it is dead code. Adding it must cite the
 * measured client cadence and the call site that produces it. The reviewed set, the single
 * {@code retainFailures = false} member, and the requirement that every member be journal-attributable
 * are pinned by {@code TenantJournalAttributionArchTest}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantJournalClientDriven {

    /**
     * Whether a failing completion is still journaled.
     *
     * @return true to journal completions with a status of 400 or above, false to omit every
     *     completion regardless of status
     */
    boolean retainFailures() default true;
}
