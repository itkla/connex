package ooo.klae.connex.backend.beans;

/**
 * The single first-response SLA state a contact is in, collapsed from the three timestamps the
 * contact carries (#559). The browser filters and facets contacts by this state rather than by the
 * raw columns, so a caller cannot ask for a combination the timestamps cannot actually hold.
 *
 * <p>A lead answered after its deadline is {@link #RESPONDED}: the breach timestamp survives on the
 * record as evidence, but the workspace's queue of leads still waiting on a first response — the
 * queue an SLA exists to drain — contains only {@link #OVERDUE}.
 */
public enum PersonFirstResponseState {

    /** A deadline is running, unanswered and not yet passed. */
    PENDING,

    /** The deadline passed with no response recorded, and none has arrived since. */
    OVERDUE,

    /** A first response was recorded, whether before or after the deadline. */
    RESPONDED
}
