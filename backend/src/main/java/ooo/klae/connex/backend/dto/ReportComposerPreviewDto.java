package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Unsaved, validated report-definition preview or a graceful unavailability result.
 * @param available whether a definition was produced
 * @param reason stable unavailability reason
 * @param definition validated definition accepted by the existing report engine
 * @param assumptionCodes closed assumptions selected for the definition
 * @param evidence server-validated vocabulary evidence
 * @param effectiveRange inclusive range the definition resolves to today
 * @param generatedAt definition generation time
 */
public record ReportComposerPreviewDto(
        boolean available,
        String reason,
        ReportDefinitionRequest definition,
        List<String> assumptionCodes,
        List<ReportComposerEvidenceDto> evidence,
        ReportRange effectiveRange,
        String generatedAt) {

    /** Returns an explicit unavailable response without a partial definition. */
    public static ReportComposerPreviewDto unavailable(String reason) {
        return new ReportComposerPreviewDto(false, reason, null, List.of(), List.of(), null, null);
    }
}
