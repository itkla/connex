package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Grid placement for one configured widget.
 * @param widgetId matching widget id
 * @param x zero-based column
 * @param y zero-based row
 * @param width column span
 * @param height row span
 */
public record ReportLayoutItem(
        @NotBlank @Size(max = 64) String widgetId,
        @Min(0) @Max(11) int x,
        @Min(0) @Max(1000) int y,
        @Min(1) @Max(12) int width,
        @Min(1) @Max(12) int height) {
}
