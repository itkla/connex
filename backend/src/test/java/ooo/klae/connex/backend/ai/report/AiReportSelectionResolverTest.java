package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Verifies the model's fact selections resolve to canonical grounded text, drop invalid selections,
 * and fail closed only when the surviving structure is not viable.
 */
class AiReportSelectionResolverTest {

    private static AiReportContext context() {
        return new AiReportContext(
                "Report",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                List.of(
                        new ReportAppendixRowDto("metric.0.0", "w0", "won_revenue · Total",
                                new BigDecimal("100"), new BigDecimal("80"), "USD"),
                        new ReportAppendixRowDto("metric.1.0", "w1", "count · Total",
                                new BigDecimal("5"), null, "count")));
    }

    private static AiReportNarrativeSelection.Item item(String sourceId, String kind) {
        return new AiReportNarrativeSelection.Item(sourceId, kind);
    }

    @Test
    void resolvesCanonicalTextAndNormalizesTitleAndDropsInvalidItems() {
        AiReportContext ctx = context();
        AiReportNarrativeSelection selection = new AiReportNarrativeSelection(
                List.of(new AiReportNarrativeSelection.Section(
                        "  EXECUTIVE SUMMARY ",
                        List.of(item("metric.0.0", "FACT"),
                                item("metric.9.9", "fact"),
                                item("metric.0.0", "fact")))),
                List.of(item("metric.1.0", "recommendation")));

        Optional<AiReportNarrativeContent> resolved =
                AiReportSelectionResolver.resolve(selection, ctx);

        assertTrue(resolved.isPresent());
        AiReportNarrativeContent content = resolved.get();
        assertEquals(1, content.sections().size());
        assertEquals("Executive summary", content.sections().getFirst().title());
        assertEquals(1, content.sections().getFirst().claims().size());
        assertEquals(AiReportFacts.fact(ctx.sources().getFirst()),
                content.sections().getFirst().claims().getFirst().text());
        assertEquals(AiReportFacts.recommendation(ctx.sources().get(1)),
                content.findings().getFirst().text());
    }

    @Test
    void failsClosedWhenNoFindingsSurvive() {
        AiReportContext ctx = context();
        AiReportNarrativeSelection selection = new AiReportNarrativeSelection(
                List.of(new AiReportNarrativeSelection.Section(
                        "Executive summary", List.of(item("metric.0.0", "fact")))),
                List.of(item("metric.9.9", "fact")));

        assertTrue(AiReportSelectionResolver.resolve(selection, ctx).isEmpty());
    }

    @Test
    void failsClosedWhenTitleIsNotAllowed() {
        AiReportContext ctx = context();
        AiReportNarrativeSelection selection = new AiReportNarrativeSelection(
                List.of(new AiReportNarrativeSelection.Section(
                        "Made Up Heading", List.of(item("metric.0.0", "fact")))),
                List.of(item("metric.1.0", "recommendation")));

        assertTrue(AiReportSelectionResolver.resolve(selection, ctx).isEmpty());
    }

    @Test
    void failsClosedWhenMoreThanHalfOfItemsAreInvalid() {
        AiReportContext ctx = context();
        AiReportNarrativeSelection selection = new AiReportNarrativeSelection(
                List.of(new AiReportNarrativeSelection.Section(
                        "Executive summary",
                        List.of(item("metric.0.0", "fact"),
                                item("metric.9.9", "fact"),
                                item("metric.8.8", "fact"),
                                item("metric.7.7", "fact")))),
                List.of(item("metric.1.0", "recommendation")));

        assertTrue(AiReportSelectionResolver.resolve(selection, ctx).isEmpty());
    }
}
