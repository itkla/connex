package ooo.klae.connex.backend.ai.assistant;

/**
 * The declared bounds every scoped assistant retrieval applies.
 *
 * <p>These are server-owned. They are echoed to the caller as part of the interpreted scope so a
 * bounded answer can state exactly what it read, and they are the same numbers the mapper receives,
 * so no path can read more than the preview promised.
 */
public final class AiChatScopeBounds {

    /** Maximum records a cohort may cover before the retrieval discloses truncation. */
    public static final int MAX_COHORT_RECORDS = 200;

    /** Maximum activity rows one bulk read returns. */
    public static final int MAX_ACTIVITY_ROWS = 100;

    /** Default activity rows one bulk read returns when the caller states no preference. */
    public static final int DEFAULT_ACTIVITY_ROWS = 50;

    /** Maximum activity rows one bulk read returns for any single record. */
    public static final int MAX_ACTIVITY_ROWS_PER_RECORD = 10;

    /** Default activity rows one bulk read returns for any single record. */
    public static final int DEFAULT_ACTIVITY_ROWS_PER_RECORD = 5;

    /** Maximum trailing window, in days, any scoped retrieval may cover. */
    public static final int MAX_PERIOD_DAYS = 365;

    /** Trailing window, in days, applied when no period is declared. */
    public static final int DEFAULT_PERIOD_DAYS = 90;

    /** Maximum open deals one bounded pipeline attention review returns. */
    public static final int MAX_ATTENTION_DEALS = 15;

    /** Cohort size at or above which a broad request is offered as an editable scope preview. */
    public static final int SCOPE_PREVIEW_RECORD_THRESHOLD = 10;

    private AiChatScopeBounds() {
    }
}
