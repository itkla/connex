package ooo.klae.connex.backend.dto;

import java.util.List;

/** Compact bounded risk projection for analytics instead of raw workspace deal assessments. */
public record DealRiskAnalyticsDto(
    List<DealRiskCurrencySummaryDto> currencies,
    boolean truncated
) {}
