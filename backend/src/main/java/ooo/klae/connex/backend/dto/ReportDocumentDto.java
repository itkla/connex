package ooo.klae.connex.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Generated narrative business report with frozen deterministic data.
 * @param definition report definition
 * @param periodStart first included current-period date
 * @param periodEnd last included current-period date
 * @param priorPeriodStart first included comparison date
 * @param priorPeriodEnd last included comparison date
 * @param narrative AI narrative layer
 * @param widgets deterministic widget data
 * @param appendix deterministic source appendix
 * @param citations server-resolved cited sources
 * @param generatedAt document generation timestamp
 * @param generation asynchronous narrative status when a cache miss was accepted
 */
public record ReportDocumentDto(
        ReportDefinitionDto definition,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate priorPeriodStart,
        LocalDate priorPeriodEnd,
        ReportNarrativeDto narrative,
        List<ReportWidgetDataDto> widgets,
        List<ReportAppendixRowDto> appendix,
        List<ReportCitationDto> citations,
        String generatedAt,
        AiGenerationStatusDto generation) {

    public ReportDocumentDto(
            ReportDefinitionDto definition,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate priorPeriodStart,
            LocalDate priorPeriodEnd,
            ReportNarrativeDto narrative,
            List<ReportWidgetDataDto> widgets,
            List<ReportAppendixRowDto> appendix,
            List<ReportCitationDto> citations,
            String generatedAt) {
        this(
                definition,
                periodStart,
                periodEnd,
                priorPeriodStart,
                priorPeriodEnd,
                narrative,
                widgets,
                appendix,
                citations,
                generatedAt,
                null);
    }
}
