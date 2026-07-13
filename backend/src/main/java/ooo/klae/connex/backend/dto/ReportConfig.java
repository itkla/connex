package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Persisted, typed report builder configuration.
 * @param widgets configured report widgets
 * @param filters shared entity filters
 * @param range custom calendar range
 * @param bucket time-series bucket key
 * @param layout grid placement
 */
public record ReportConfig(
        @NotEmpty @Size(max = 16) List<@Valid ReportWidgetConfig> widgets,
        @Valid ReportFilters filters,
        @Valid ReportRange range,
        @NotNull @Size(max = 16) String bucket,
        @NotEmpty @Size(max = 16) List<@Valid ReportLayoutItem> layout) {
}
