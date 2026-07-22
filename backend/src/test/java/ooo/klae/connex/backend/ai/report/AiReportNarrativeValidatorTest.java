package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * The cache-read re-validation guards resolved, fully-grounded content against registry drift.
 */
class AiReportNarrativeValidatorTest {

    private static AiReportContext context() {
        return new AiReportContext(
                "Monthly review", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                List.of(new ReportAppendixRowDto("metric.0.0", "w0", "won_revenue · Total",
                        new BigDecimal("1000000"), new BigDecimal("950000"), "USD")));
    }

    private static AiReportNarrativeContent content(String text, List<String> sourceIds) {
        AiReportNarrativeContent.Claim claim = new AiReportNarrativeContent.Claim(text, sourceIds);
        return new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("Executive summary", List.of(claim))),
                List.of(claim));
    }

    @Test
    void resolvedContentPasses() {
        assertTrue(AiReportNarrativeValidator.validate(
                content("Won revenue rose to $1,000,000.", List.of("metric.0.0")), context()).isPresent());
    }

    @Test
    void unknownCitationFailsClosed() {
        assertTrue(AiReportNarrativeValidator.validate(
                content("Won revenue rose.", List.of("metric.9.9")), context()).isEmpty());
    }

    @Test
    void leftoverPlaceholderFailsClosed() {
        assertTrue(AiReportNarrativeValidator.validate(
                content("Won revenue rose to {{num:metric.0.0.current}}.", List.of("metric.0.0")),
                context()).isEmpty());
    }

    @Test
    void unallowedTitleFailsClosed() {
        AiReportNarrativeContent.Claim claim =
                new AiReportNarrativeContent.Claim("Won revenue rose.", List.of("metric.0.0"));
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("Made up heading", List.of(claim))),
                List.of(claim));
        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }

    @Test
    void emptyFindingsFailsClosed() {
        AiReportNarrativeContent.Claim claim =
                new AiReportNarrativeContent.Claim("Won revenue rose.", List.of("metric.0.0"));
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("Executive summary", List.of(claim))),
                List.of());
        assertTrue(AiReportNarrativeValidator.validate(content, context()).isEmpty());
    }
}
