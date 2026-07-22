package ooo.klae.connex.backend.dto;

/**
 * Saved workspace-shared report definition.
 * @param id definition id
 * @param name report name
 * @param description optional description
 * @param cadence cadence key
 * @param templateKey optional built-in template key
 * @param config typed builder configuration
 * @param createdBy creator user id
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record ReportDefinitionDto(
        int id,
        String name,
        String description,
        String cadence,
        String templateKey,
        ReportConfig config,
        Integer createdBy,
        String createdAt,
        String updatedAt) {
}
