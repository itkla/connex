package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * AI narrative layer or a stable unavailable result.
 * @param available whether narrative generation succeeded
 * @param sections ordered narrative sections
 * @param findings sourced findings and recommendations
 * @param reason stable unavailability reason
 * @param generatedAt generation timestamp
 * @param warnings demasking warning count
 */
public record ReportNarrativeDto(
        boolean available,
        List<ReportNarrativeSectionDto> sections,
        List<ReportNarrativeClaimDto> findings,
        String reason,
        String generatedAt,
        int warnings) {

    /** Creates a graceful unavailable narrative. */
    public static ReportNarrativeDto unavailable(String reason) {
        return new ReportNarrativeDto(false, List.of(), List.of(), reason, null, 0);
    }
}
