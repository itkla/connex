package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Per-currency deal metrics and the total number of deals matching the filter.
 */
public record DealMetricsDto(
    List<DealCurrencyMetricsDto> byCurrency,
    long totalCount
) {}
