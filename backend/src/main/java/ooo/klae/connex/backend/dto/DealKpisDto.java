package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Current and previous deal KPIs with ordered trend series for the active workspace.
 */
public record DealKpisDto(
    double wonRevenue,
    Double wonRevenuePrev,
    double newPipeline,
    Double newPipelinePrev,
    long wonCount,
    long lostCount,
    double wonValue,
    double lostValue,
    Long wonCountPrev,
    Long lostCountPrev,
    double avgCycleDays,
    Double avgCycleDaysPrev,
    List<Double> wonSeries,
    List<Double> newPipelineSeries,
    List<Double> winRateSeries,
    List<Double> avgCycleSeries
) {}
