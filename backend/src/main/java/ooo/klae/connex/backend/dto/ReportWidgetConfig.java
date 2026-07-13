package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One configured report widget.
 * @param id stable client-generated widget id
 * @param title optional display title
 * @param dataSource entity source key
 * @param measure deterministic measure key
 * @param groupBy grouping key
 * @param chartType presentation key
 */
public record ReportWidgetConfig(
        @NotBlank @Size(max = 64) String id,
        @Size(max = 160) String title,
        @NotBlank @Size(max = 32) String dataSource,
        @NotBlank @Size(max = 32) String measure,
        @Size(max = 32) String groupBy,
        @NotBlank @Size(max = 16) String chartType) {
}
