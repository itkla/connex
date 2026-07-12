package ooo.klae.connex.backend.dto;

import java.util.List;

/** Bounded deal-risk totals for one currency. */
public record DealRiskCurrencySummaryDto(
    String currency,
    double value,
    long count,
    long high,
    long medium,
    long low,
    List<DealRiskFactorCountDto> factors
) {}
