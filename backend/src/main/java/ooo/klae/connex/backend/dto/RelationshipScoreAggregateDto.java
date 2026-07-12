package ooo.klae.connex.backend.dto;

/** Compact, server-aggregated touch inputs for one live relationship score. */
public record RelationshipScoreAggregateDto(
    int id,
    double rawWeight,
    double recentWeight,
    double priorWeight,
    String lastTouchAt,
    int recentTouchCount
) {}
