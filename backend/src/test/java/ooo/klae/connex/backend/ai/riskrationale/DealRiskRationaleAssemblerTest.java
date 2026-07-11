package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.services.DealService;

@ExtendWith(MockitoExtension.class)
class DealRiskRationaleAssemblerTest {
    private static final int WORKSPACE_ID = 13;
    private static final int DEAL_ID = 41;
    private static final int PERSON_ID = 73;

    @Mock private DealService dealService;

    private DealRiskRationaleAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new DealRiskRationaleAssembler(dealService);
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
        assertFalse(serialized.contains("Acme Holdings"));
        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("Olivia Chen"));
        assertFalse(serialized.contains("mina.patel@acme.example"));
        assertFalse(serialized.contains("+1 415 555 0199"));
        verify(dealService).getDealSummary(DEAL_ID);
        verify(dealService).getPeopleByDealId(DEAL_ID);
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
