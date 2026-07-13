package ooo.klae.connex.backend.dto;

/**
 * Built-in starting point for a report definition.
 * @param key stable template key
 * @param name default display name
 * @param description default description
 * @param cadence default cadence
 * @param config default builder configuration
 */
public record ReportTemplateDto(
        String key,
        String name,
        String description,
        String cadence,
        ReportConfig config) {
}
