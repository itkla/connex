package ooo.klae.connex.backend.dto;

/**
 * Workspace-wide warmth distribution, trend, and decay counts.
 */
public record WarmthSummaryDto(
    BandCounts contacts,
    BandCounts companies,
    TrendCounts contactTrends,
    DecayCounts contactDecay
) {}
