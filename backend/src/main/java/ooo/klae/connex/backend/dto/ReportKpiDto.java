package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Deterministic scalar projection of one saved report widget.
 * @param reportId saved report definition id
 * @param reportName saved report name
 * @param widgetId configured widget id
 * @param title display title
 * @param chartType presentation key
 * @param dataSource entity source key
 * @param measure measure key
 * @param groupBy grouping key
 * @param unit value unit
 * @param total current-period total, or {@code null} when unavailable
 * @param priorTotal prior-period total
 * @param changePercent prior-period percentage change
 * @param periodStart first included current-period date
 * @param periodEnd last included current-period date
 * @param priorStart first included prior-period date
 * @param priorEnd last included prior-period date
 * @param generatedAt generation timestamp
 * @param available whether the current-period scalar is safe to publish
 * @param reason {@code mixed_currency}, {@code non_additive}, or {@code undefined}; otherwise
 *     {@code null}
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
