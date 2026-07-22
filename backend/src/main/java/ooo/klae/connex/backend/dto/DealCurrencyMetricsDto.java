package ooo.klae.connex.backend.dto;

/**
 * Deal summary metrics for a single currency across the matching workspace deals.
 */
public record DealCurrencyMetricsDto(
    String currency,
    long openCount,
    double openValue,
    long closedCount,
    double closedForecast,
    double closedRevenue,
    long wonCount,
    long lostCount
) {}
