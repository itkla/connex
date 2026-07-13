package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Server-resolved citation for a deterministic report source.
 * @param sourceId source registry id
 * @param widgetId owning widget id
 * @param label source label
 * @param value exact deterministic value
 * @param priorValue exact prior-period value when the metric supports comparison
 * @param unit value unit
 */
public record ReportCitationDto(
        String sourceId,
        String widgetId,
        String label,
        BigDecimal value,
        BigDecimal priorValue,
        String unit) {
}
