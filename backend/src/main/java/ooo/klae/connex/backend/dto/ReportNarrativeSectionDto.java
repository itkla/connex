package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Titled narrative report section.
 * @param title section title
 * @param claims sourced narrative claims
 */
public record ReportNarrativeSectionDto(String title, List<ReportNarrativeClaimDto> claims) {
}
