package ooo.klae.connex.backend.dto;

/**
 * Mapper projection for deal KPIs within one comparison period.
 */
public record DealKpiPeriodDto(
    double wonRevenue,
    double newPipeline,
    long newPipelineCount,
    long wonCount,
    long lostCount,
    double lostValue,
    double avgCycleDays
) {}
