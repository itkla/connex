package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import tools.jackson.databind.ObjectMapper;

class AiReportAssemblerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiReportAssembler assembler = new AiReportAssembler(CLOCK);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void assembleMasksReportNameAndOpaqueFigureLabelWithOneBinding() throws Exception {
        String companyName = "Acme Holdings K.K.";
        AiReportContext context = new AiReportContext(
                companyName,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 13),
                List.of(new ReportAppendixRowDto(
                        "metric.0.0",
                        "widget-1",
                        companyName + " · " + companyName,
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        "count")));

        AiReportAssembly assembly = assembler.assemble(context);
        String serialized = serialize(assembly);
        String modelVisibleText = assembly.prompt().getMessages().getFirst().getContent();

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(
                serialized, assembly.context(), objectMapper));
        assertTrue(modelVisibleText.contains("{{C1}}"));
        assertFalse(modelVisibleText.toLowerCase(Locale.ROOT)
                .contains(companyName.toLowerCase(Locale.ROOT)));
    }

    @Test
    void assembleOmitsTenantGroupCollisionsWithoutMutatingTrustedStaticText() throws Exception {
        AiReportContext context = new AiReportContext(
                "Quarterly relationship review",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 13),
                List.of(new ReportAppendixRowDto(
                        "metric.0.0",
                        "widget-1",
                        "warm_intro_opportunity_value · Warm",
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        "JPY")));

        AiReportAssembly assembly = assembler.assemble(context);
        String serialized = serialize(assembly);
        String modelVisibleText = assembly.prompt().getMessages().getFirst().getContent();

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(
                serialized, assembly.context(), objectMapper));
        assertTrue(modelVisibleText.contains("Measure: Warm-intro opportunity value"));
        assertTrue(modelVisibleText.contains("Group: [tenant label omitted]"));
        assertTrue(assembly.prompt().getSystemPrompt().contains("warm path"));
    }

    @Test
    void assembleOmitsTenantLabelsThatCollideWithRegistryAndFigureGrammar() throws Exception {
        AiReportContext context = new AiReportContext(
                "Report",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 13),
                List.of(new ReportAppendixRowDto(
                        "metric.0.0",
                        "widget-1",
                        "won_revenue · current",
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        "JPY")));

        AiReportAssembly assembly = assembler.assemble(context);
        String serialized = serialize(assembly);
        String modelVisibleText = assembly.prompt().getMessages().getFirst().getContent();

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(
                serialized, assembly.context(), objectMapper));
        assertTrue(modelVisibleText.contains("Report: [tenant label omitted]"));
        assertTrue(modelVisibleText.contains("Group: [tenant label omitted]"));
        assertTrue(modelVisibleText.contains("{{num:metric.0.0.current}}"));
    }

    @Test
    void assembleOmitsSubstringEnvelopeAndSentinelCollisions() throws Exception {
        AiReportContext context = new AiReportContext(
                "Port",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 13),
                List.of(
                        new ReportAppendixRowDto(
                                "metric.0.0",
                                "widget-1",
                                "won_revenue · system",
                                BigDecimal.TEN,
                                BigDecimal.ONE,
                                "JPY"),
                        new ReportAppendixRowDto(
                                "metric.0.1",
                                "widget-1",
                                "won_revenue · tenant label omitted",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                "JPY")));

        AiReportAssembly assembly = assembler.assemble(context);
        String serialized = serialize(assembly);
        String modelVisibleText = assembly.prompt().getMessages().getFirst().getContent();

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(
                serialized, assembly.context(), objectMapper));
        assertTrue(modelVisibleText.contains("Report: [tenant label omitted]"));
        assertTrue(modelVisibleText.contains("metric.0.0; Measure: Won revenue; "
                + "Group: [tenant label omitted]"));
        assertTrue(modelVisibleText.contains("metric.0.1; Measure: Won revenue; "
                + "Group: [tenant label omitted]"));
    }

    @Test
    void assembleOmitsLabelsCollidingWithTaggedReasoningEnvelope() throws Exception {
        AiReportContext context = new AiReportContext(
                "Quarterly relationship review",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 13),
                List.of(new ReportAppendixRowDto(
                        "metric.0.0",
                        "widget-1",
                        "thinking",
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        "count")));

        AiReportAssembly assembly = assembler.assemble(context);
        String finalEnvelope = objectMapper.writeValueAsString(Map.of(
                "system", assembly.prompt().getSystemPrompt() + "\n\n"
                        + AiInvocationService.TAGGED_REASONING_INSTRUCTION,
                "messages", assembly.prompt().getMessages().stream()
                        .map(AiReportAssemblerTest::message)
                        .toList()));

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak(
                finalEnvelope, assembly.context(), objectMapper));
        assertTrue(assembly.prompt().getMessages().getFirst().getContent()
                .contains("Measure: [tenant label omitted]"));
    }

    private String serialize(AiReportAssembly assembly) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "system", assembly.prompt().getSystemPrompt(),
                "messages", assembly.prompt().getMessages().stream()
                        .map(AiReportAssemblerTest::message)
                        .toList()));
    }

    private static Map<String, String> message(MaskedMessage message) {
        return Map.of("role", message.getRole(), "content", message.getContent());
    }
}
