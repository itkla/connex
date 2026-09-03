package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Deterministic scalar projection of one saved report widget.
 *
 * <p>This projection is equal-or-unavailable relative to full report generation for the same saved
 * configuration and period: when {@code available} is {@code true}, {@code total} and {@code unit}
 * are byte-identical to the generated widget figure. If bounded evaluation would exceed its input
 * ceiling, {@code available} is {@code false}, {@code total} and {@code unit} are {@code null}, and
 * {@code reason} is {@code input_limit_exceeded}.
 * @param reportId saved report definition id
 * @param reportName saved report name
 * @param widgetId configured widget id
 * @param title display title
 * @param chartType presentation key
 * @param dataSource entity source key
 * @param measure measure key
 * @param groupBy grouping key
 * @param unit value unit, or {@code null} when bounded evaluation exceeds its input ceiling
 * @param total current-period total, or {@code null} when unavailable
 * @param priorTotal prior-period total
 * @param changePercent prior-period percentage change
 * @param periodStart first included current-period date
 * @param periodEnd last included current-period date
 * @param priorStart first included prior-period date
 * @param priorEnd last included prior-period date
 * @param generatedAt generation timestamp
 * @param available whether the current-period scalar is safe to publish
 * @param reason closed unavailable-reason vocabulary: {@code mixed_currency},
 *     {@code non_additive}, {@code undefined}, or {@code input_limit_exceeded}; {@code null} when
 *     available
 */
public record ReportKpiDto(
        int reportId,
        String reportName,
        String widgetId,
        String title,
        String chartType,
        String dataSource,
        String measure,
        String groupBy,
        String unit,
        BigDecimal total,
        BigDecimal priorTotal,
        BigDecimal changePercent,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate priorStart,
        LocalDate priorEnd,
        String generatedAt,
        boolean available,
        String reason) {
}
