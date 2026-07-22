package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a saved report definition.
 * @param name report name
 * @param description optional description
 * @param cadence cadence key
 * @param templateKey optional built-in template key
 * @param config typed builder configuration
 */
public record ReportDefinitionRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotBlank @Size(max = 16) String cadence,
        @Size(max = 64) String templateKey,
        @NotNull @Valid ReportConfig config) {
}
