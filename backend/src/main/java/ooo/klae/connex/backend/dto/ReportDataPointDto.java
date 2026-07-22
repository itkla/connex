package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * One deterministic chart or table point.
 * @param key stable group key
 * @param label display label
 * @param value current-period value
 * @param priorValue prior-period value
 * @param sourceId source registry id
 */
public record ReportDataPointDto(
        String key,
        String label,
        BigDecimal value,
        BigDecimal priorValue,
        String sourceId) {
}
