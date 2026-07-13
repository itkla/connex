package ooo.klae.connex.backend.ai.report;

import java.util.List;

/**
 * Structured model and cache payload for a grounded report narrative.
 * @param sections ordered executive-summary and commentary sections
 * @param findings ordered findings and recommendations
 */
public record AiReportNarrativeContent(List<Section> sections, List<Claim> findings) {

    /**
     * Titled narrative section composed only of individually sourced claims.
     * @param title short plain-text title
     * @param claims ordered sourced claims
     */
    public record Section(String title, List<Claim> claims) {
    }

    /**
     * One plain-text narrative claim and the deterministic source ids that support it.
     * @param text claim text
     * @param sourceIds cited source registry ids
     */
    public record Claim(String text, List<String> sourceIds) {
    }
}
