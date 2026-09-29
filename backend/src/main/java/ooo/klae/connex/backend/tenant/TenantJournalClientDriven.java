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
 * for, and its volume is then bounded by the member actions that drive the client loop.
 *
 * <p>{@link #retainFailures()} is false where the client re-issues the call with no member action
 * able to stop it, so retaining its failures would be unbounded in time rather than bounded by
 * member actions. Two drivers have that shape: a self-rescheduling heartbeat whose failure handler
 * reschedules regardless, and the realtime socket's own reconnect, which re-runs the whole refresh
 * fan-out on every reconnect for as long as a surface stays open. The bound matters because the
 * support bundle's journal projection raises rather than truncating above its record cap, so an
 * unbounded failure loop would deny the operator an archive during exactly the incident they are
 * collecting for.
 *
 * <p>The marker is only meaningful on a handler that is already journal-attributable through
 * {@link TenantJournalAttributable}; on any other handler it is dead code. Adding it must cite the
 * measured client cadence and the call site that produces it, or record that no shipped client
 * calls the route at all. The reviewed set, the {@code retainFailures = false} members, and the
 * requirement that every member be journal-attributable are pinned by
 * {@code TenantJournalAttributionArchTest}.
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
