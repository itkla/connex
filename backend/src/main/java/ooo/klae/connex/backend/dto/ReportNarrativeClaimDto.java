package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * One narrative claim with server-validated sources.
 * @param text claim text
 * @param sourceIds cited source ids
 */
public record ReportNarrativeClaimDto(String text, List<String> sourceIds) {
}
