package ooo.klae.connex.backend.beans;

/**
 * The reason a contact was disqualified from its lead lifecycle.
 *
 * <p>This vocabulary is fixed for the first lifecycle increment. Making it workspace-configurable is
 * tracked separately (#559); the column deliberately carries no database CHECK on these values so
 * that later work can widen it without a data migration.
 */
public enum PersonDisqualificationReason {
    /** No budget is available for the foreseeable evaluation window. */
    NO_BUDGET,
    /** The product does not fit the need. */
    NO_FIT,
    /** No route to a decision maker. */
    NO_AUTHORITY,
    /** Real need, wrong time, with no date worth nurturing towards. */
    BAD_TIMING,
    /** Committed to a competitor. */
    COMPETITOR,
    /** Already represented by another contact or deal. */
    DUPLICATE,
    /** Repeated contact attempts went unanswered. */
    UNRESPONSIVE,
    /** Not a genuine inquiry. */
    SPAM,
    /** Anything else. A note explaining it is required. */
    OTHER
}
