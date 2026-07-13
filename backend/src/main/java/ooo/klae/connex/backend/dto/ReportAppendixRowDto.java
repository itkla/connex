package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Frozen deterministic appendix row and citation source.
 * @param sourceId stable source registry id
 * @param widgetId owning widget id
 * @param label source label
 * @param value current-period value
 * @param priorValue prior-period value
 * @param unit value unit
 */
public record ReportAppendixRowDto(
        String sourceId,
        String widgetId,
        String label,
        BigDecimal value,
        BigDecimal priorValue,
        String unit) {
}
