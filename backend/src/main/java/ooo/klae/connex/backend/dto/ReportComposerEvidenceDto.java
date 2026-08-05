package ooo.klae.connex.backend.dto;

/**
 * Server-validated report vocabulary selected for one proposed evidence block.
 * @param widgetId proposed widget identifier
 * @param dataSource deterministic server data source
 * @param measure deterministic server measure
 * @param groupBy supported grouping
 * @param chartType supported presentation
 */
public record ReportComposerEvidenceDto(
        String widgetId,
        String dataSource,
        String measure,
        String groupBy,
        String chartType) {
}
