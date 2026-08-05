package ooo.klae.connex.backend.ai.report;

import java.util.List;

import ooo.klae.connex.backend.dto.ReportConfig;

/**
 * Structured provider output for an unsaved report definition.
 * @param cadence report cadence key
 * @param config typed report engine configuration
 * @param assumptionCodes closed definition assumption codes
 */
public record AiReportComposerContent(
        String cadence,
        ReportConfig config,
        List<String> assumptionCodes) {
}
