package ooo.klae.connex.backend.dto;

/**
 * Internal aggregate and ranked-source row used to assemble relationship evidence.
 */
public record RelationshipEvidenceRowDto(
    String sourceType,
    int sourceId,
    String interactionType,
    String occurredAt,
    double baseWeight,
    double decayedContribution,
    int totalContributorCount,
    double totalDecayedContribution,
    int activityCount,
    int noteCount,
    int taskCount,
    double recentWeight,
    double priorWeight,
    String lastTouchAt,
    int recentTouchCount
) {}
