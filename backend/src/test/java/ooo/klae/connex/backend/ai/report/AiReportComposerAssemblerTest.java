package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;

class AiReportComposerAssemblerTest {
    private final AiReportComposerAssembler assembler = new AiReportComposerAssembler();

    @Test
    void assemble_masksFreeTextAndProvidesStaticVocabularyWithoutCrmRecords() {
        Optional<AiReportComposerAssembly> result = assembler.assemble(
                "Show pipeline and email alice@example.com", LocalDate.of(2026, 8, 5));

        assertTrue(result.isPresent());
        String serialized = serialized(result.orElseThrow().prompt());
        assertTrue(serialized.contains("Today is 2026-08-05"));
        assertTrue(serialized.contains("REPORT_REQUEST_BEGIN"));
        assertTrue(serialized.contains("Show pipeline and email [redacted]"));
        assertTrue(serialized.contains("This task proposes a definition only"));
        assertFalse(serialized.contains("alice@example.com"));
    }

    @Test
    void assemble_rejectsBlankRequest() {
        assertTrue(assembler.assemble("  ", LocalDate.of(2026, 8, 5)).isEmpty());
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
