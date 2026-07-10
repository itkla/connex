package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Realized and projected deal revenue grouped by calendar month.
 */
public record DealRevenueSeriesDto(
    List<DealMonthTotalDto> closed,
    List<DealMonthTotalDto> projected
) {}
