package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.ai.AiRelationshipContext;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.ScoringService;

@ExtendWith(MockitoExtension.class)
class DealRiskRationaleAssemblerTest {
    private static final int WORKSPACE_ID = 13;
    private static final int DEAL_ID = 41;
    private static final int PERSON_ID = 73;

    @Mock private DealService dealService;
    @Mock private ScoringService scoringService;
    @Mock private AiRelationshipContext aiRelationshipContext;

    private DealRiskRationaleAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new DealRiskRationaleAssembler(dealService, scoringService, aiRelationshipContext);
    }

    @Test
    void assemble_masksAllIdentifiersAndUsesStakeholderTokenInColdFactor() {
        Person person = new Person();
        person.setId(PERSON_ID);
        person.setName("Mina Patel");
        person.setEmail("mina.patel@acme.example");
        person.setPhone("+1 415 555 0199");
        DealPerson stakeholder = new DealPerson(person, "Decision maker at Acme Holdings");
        DealSummaryDto summary = new DealSummaryDto(
                DEAL_ID,
                "Acme expansion",
                125000,
                "USD",
                "open",
                "2026-07-05",
                "Evaluation for Acme Holdings",
                "Enterprise",
                "Acme Holdings",
                "Olivia Chen");
        DealRiskFactor coldFactor = new DealRiskFactor(
                "stakeholder_cold",
                "high",
                Map.of(
                        "personId", PERSON_ID,
                        "person", "Mina Patel",
                        "role", "Decision maker at Acme Holdings",
                        "band", "cold",
                        "daysSinceTouch", 45,
                        "email", "mina.patel@acme.example"));
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID,
                125000,
                "USD",
                "high",
                75,
                List.of(coldFactor),
                "2026-07-09 18:30:00");

        when(dealService.getDealSummary(DEAL_ID)).thenReturn(summary);
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of(stakeholder));

        RationaleAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID, risk);
        String serialized = serialized(assembly.prompt());

        assertTrue(serialized.contains("{{C1}}"));
        assertTrue(serialized.contains("{{P1}}"));
        assertTrue(serialized.contains("person={{P2}}"));
        assertTrue(serialized.contains("role=Decision maker at {{C1}}"));
        assertTrue(serialized.contains("Close timing: 4 days overdue"));
        assertTrue(serialized.contains("Value: 125000 USD"));
        assertFalse(serialized.contains("125000.0"));
        assertFalse(serialized.contains("Acme Holdings"));
        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("Olivia Chen"));
        assertFalse(serialized.contains("mina.patel@acme.example"));
        assertFalse(serialized.contains("+1 415 555 0199"));
        verify(dealService).getDealSummary(DEAL_ID);
        verify(dealService).getPeopleByDealId(DEAL_ID);
    }

    @Test
    void assemble_largeDealValueUsesPlainDecimalNotation() {
        DealSummaryDto summary = new DealSummaryDto(
                DEAL_ID,
                "Enterprise renewal",
                5.0E7,
                "JPY",
                "open",
                "2026-08-01",
                "Renewal",
                "Enterprise",
                "BigCo",
                "Owner Name");
        DealRiskFactor factor = new DealRiskFactor("close_overdue", "high", Map.of("daysOverdue", 5));
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 5.0E7, "JPY", "high", 80, List.of(factor), "2026-07-09 18:30:00");

        when(dealService.getDealSummary(DEAL_ID)).thenReturn(summary);
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of());

        String serialized = serialized(assembler.assemble(WORKSPACE_ID, DEAL_ID, risk).prompt());

        assertTrue(serialized.contains("Value: 50000000 JPY"));
        assertFalse(serialized.contains("5.0E7"));
        assertFalse(serialized.contains("E7"));
    }

    @Test
    void assemble_japaneseLocaleTranslatesOnlyJsonStringValues() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 0, "JPY", "medium", 25, List.of(), "2026-07-09 18:30:00");
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        try {
            String systemPrompt = assembler.assemble(WORKSPACE_ID, DEAL_ID, risk).prompt().getSystemPrompt();

            assertTrue(systemPrompt.contains("Write every JSON string value in Japanese"));
            assertTrue(systemPrompt.contains("keep all JSON property names"));
            assertTrue(systemPrompt.contains("in English exactly as specified"));
            assertTrue(systemPrompt.contains("do not translate the keys"));
            assertTrue(systemPrompt.contains("\"narrative\""));
            assertTrue(systemPrompt.contains("\"actions\""));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
