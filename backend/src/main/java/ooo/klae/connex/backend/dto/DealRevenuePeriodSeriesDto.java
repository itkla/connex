package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Realized and projected deal revenue across aligned analytics periods.
 */
public record DealRevenuePeriodSeriesDto(
    List<DealPeriodTotalDto> realized,
    List<DealPeriodTotalDto> projected
) {}
