package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deterministic data block for one report widget.
 * @param widgetId configured widget id
 * @param title display title
 * @param chartType presentation key
 * @param dataSource entity source key
 * @param measure measure key
 * @param groupBy grouping key
 * @param unit value unit
 * @param total current-period total
 * @param priorTotal prior-period total
 * @param changePercent prior-period percentage change
 * @param points grouped data points
 */
public record ReportWidgetDataDto(
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
        List<ReportDataPointDto> points) {
}
