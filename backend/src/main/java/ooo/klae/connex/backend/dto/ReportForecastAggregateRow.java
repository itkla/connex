package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * One atomic group of deterministic forecast bands returned by the report mapper.
 * @param groupKey stable currency-qualified group key
 * @param groupLabel display label
 * @param unit currency unit
 * @param bestValue ceiling where every open deal closes won
 * @param weightedValue stage-win-rate-weighted likely value
 * @param worstValue conservative squared-rate commit value
 */
public record ReportForecastAggregateRow(
        String groupKey,
        String groupLabel,
        String unit,
        BigDecimal bestValue,
        BigDecimal weightedValue,
        BigDecimal worstValue) {
}
