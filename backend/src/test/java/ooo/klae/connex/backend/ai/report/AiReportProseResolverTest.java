package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * The prose resolver fills figure placeholders, drops ungrounded claims, and its pre-demask guard
 * rejects any literal figure the model typed.
 */
class AiReportProseResolverTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AiReportContext context() {
        return new AiReportContext(
                "Review", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                List.of(new ReportAppendixRowDto("metric.0.0", "w0", "won_revenue · Total",
                        new BigDecimal("1000000"), new BigDecimal("800000"), "USD")));
    }

    private static AiReportFigures figures(AiReportContext context) {
        return AiReportFigures.from(context.sources(), Locale.ENGLISH);
    }

    private static AiReportNarrativeContent one(String text, List<String> sourceIds) {
        AiReportNarrativeContent.Claim claim = new AiReportNarrativeContent.Claim(text, sourceIds);
        return new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("Executive summary", List.of(claim))),
                List.of(claim));
    }

    @Test
    void fillsPlaceholdersWithExactFigures() {
        AiReportContext ctx = context();
        Optional<AiReportNarrativeContent> resolved = AiReportProseResolver.resolve(
                one("Won revenue rose to {{num:metric.0.0.current}} ({{num:metric.0.0.delta_pct}}).",
                        List.of("metric.0.0")),
                ctx, figures(ctx));

        assertTrue(resolved.isPresent());
        String text = resolved.get().sections().getFirst().claims().getFirst().text();
        assertTrue(text.contains("$1,000,000"), text);
        assertTrue(text.contains("+25.0%"), text);
        assertFalse(text.contains("{{"));
    }

    @Test
    void unknownPlaceholderDropsClaimAndFailsClosed() {
        AiReportContext ctx = context();
        Optional<AiReportNarrativeContent> resolved = AiReportProseResolver.resolve(
                one("Revenue was {{num:metric.0.0.nonsense}}.", List.of("metric.0.0")), ctx, figures(ctx));
        assertTrue(resolved.isEmpty());
    }

    @Test
    void unknownCitationDropsClaimAndFailsClosed() {
        AiReportContext ctx = context();
        Optional<AiReportNarrativeContent> resolved = AiReportProseResolver.resolve(
                one("Revenue rose.", List.of("metric.9.9")), ctx, figures(ctx));
        assertTrue(resolved.isEmpty());
    }

    @Test
    void guardRejectsLiteralDigitInProseButAllowsPlaceholdersAndSourceIds() {
        AiReportContext ctx = context();
        assertFalse(AiReportProseResolver.noLiteralFigures().permits(
                node("Revenue grew 25% this period.", "metric.0.0")));
        assertTrue(AiReportProseResolver.noLiteralFigures().permits(
                node("Revenue grew {{num:metric.0.0.delta_pct}} this period.", "metric.0.0")));
    }

    private static ObjectNode node(String text, String sourceId) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode claim = MAPPER.createObjectNode();
        claim.put("text", text);
        claim.putArray("sourceIds").add(sourceId);
        root.putArray("findings").add(claim);
        return root;
    }

    @Test
    void lowercaseTitleSnapsToCanonicalSoCacheReadRevalidationPasses() {
        AiReportContext ctx = context();
        AiReportNarrativeContent.Claim claim =
                new AiReportNarrativeContent.Claim("Won revenue is {{num:metric.0.0.current}}.", List.of("metric.0.0"));
        AiReportNarrativeContent input = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section("  executive SUMMARY ", List.of(claim))),
                List.of(claim));

        Optional<AiReportNarrativeContent> resolved =
                AiReportProseResolver.resolve(input, ctx, figures(ctx));

        assertTrue(resolved.isPresent());
        assertEquals("Executive summary", resolved.get().sections().getFirst().title());
        assertTrue(AiReportNarrativeValidator.validate(resolved.get(), ctx).isPresent());
    }

    @Test
    void figuresFormatDeltaAndCurrency() {
        AiReportFigures figures = figures(context());
        assertEquals("$1,000,000", figures.resolve("num:metric.0.0.current"));
        assertEquals("$800,000", figures.resolve("num:metric.0.0.prior"));
        assertEquals("$200,000", figures.resolve("num:metric.0.0.delta_abs"));
        assertEquals("+25.0%", figures.resolve("num:metric.0.0.delta_pct"));
    }
}
