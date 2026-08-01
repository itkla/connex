package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/** Bounded deal-risk totals for one currency. */
public record DealRiskCurrencySummaryDto(
    String currency,
    BigDecimal value,
    long count,
    long high,
    long medium,
    long low,
    List<DealRiskFactorCountDto> factors
) {}
