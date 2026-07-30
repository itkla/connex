package ooo.klae.connex.backend.dto;

/**
 * One ranked source row backing a relationship score, bounded by the server's contributor limit.
 */
public record RelationshipEvidenceRowDto(
    String sourceType,
    int sourceId,
    String interactionType,
    String occurredAt,
    double baseWeight,
    double decayedContribution
) {}
