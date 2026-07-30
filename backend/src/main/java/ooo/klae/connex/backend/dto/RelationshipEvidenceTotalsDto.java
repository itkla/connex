package ooo.klae.connex.backend.dto;

/**
 * One aggregation pass over every eligible source attributed to a scored record.
 *
 * <p>The counts and weights cover the whole eligible set, not just the contributors the evidence
 * response returns, and they are the inputs the reported temperature is derived from.
 */
public record RelationshipEvidenceTotalsDto(
    int contributorCount,
    double totalDecayedContribution,
    int activityCount,
    int noteCount,
    int taskCount,
    double recentWeight,
    double priorWeight,
    String lastTouchAt,
    int recentTouchCount
) {}
