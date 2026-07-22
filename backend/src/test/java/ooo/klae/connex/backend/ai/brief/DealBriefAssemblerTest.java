package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.ScoringService;

@ExtendWith(MockitoExtension.class)
class DealBriefAssemblerTest {
    private static final int WORKSPACE_ID = 13;
    private static final int DEAL_ID = 41;
    private static final int PERSON_ID = 73;

    @Mock private DealService dealService;
    @Mock private ScoringService scoringService;
    @Mock private DealRiskService dealRiskService;
    @Mock private AiRelationshipContext aiRelationshipContext;
    @Mock private PersonMapper personMapper;

    private DealBriefAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new DealBriefAssembler(
            dealService, scoringService, dealRiskService, aiRelationshipContext, personMapper);
        lenient().when(personMapper.getByIds(eq(WORKSPACE_ID), anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            List<Person> people = new ArrayList<>();
            for (Object value : ids) {
                if (value instanceof Integer id) {
                    Person person = new Person();
                    person.setId(id);
                    people.add(person);
                }
            }
            return people;
        });
    }

    @Test
    void assemble_masksIdentifiersExcludesContactFieldsAndOmitsSpecialCareNote() {
        Deal deal = deal();
        Person person = person();
        DealPerson stakeholder = new DealPerson(person, "Decision maker");
        DealSummaryDto summary = new DealSummaryDto(
                DEAL_ID,
                "Acme expansion",
                125000,
                0,
                "USD",
                "open",
                "2026-08-31",
                "Evaluation",
                "Enterprise",
                "Acme",
                "Owner Name");
        DealStageHistory history = new DealStageHistory();
        history.setStageName("Discovery");
        history.setAchievedAt("2026-07-01 09:00:00");
        Activity activity = new Activity();
        activity.setSubject("Mina Patel met the team at Acme");
        activity.setNotes("Budget review went well");
        activity.setTimestamp("2026-07-08 14:00:00");
        Note note = new Note();
        note.setTitle("Call background");
        note.setContent("Mina Patel discussed a diagnosis and follow-up details.");
        note.setCreatedAt("2026-07-07 16:00:00");
        Task task = new Task();
        task.setDescription("Send Acme the revised proposal");
        task.setDueDate("2026-07-10");
        task.setCompleted(false);

        RelationshipTemperatureDto temperature = new RelationshipTemperatureDto(
                PERSON_ID, 64, "warm", "cooling", "2026-06-29 10:00:00", 10, 2, null, null);
        DealRiskFactor riskFactor = new DealRiskFactor(
                "stakeholder_cold",
                "medium",
                Map.of("person", "Mina Patel", "email", "mina.patel@acme.example"));
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 0.0, null, "medium", 25, List.of(riskFactor), "2026-07-09 18:30:00");

        when(dealService.getDealById(DEAL_ID)).thenReturn(deal);
        when(dealService.getDealSummary(DEAL_ID)).thenReturn(summary);
        when(dealService.getStageHistory(DEAL_ID)).thenReturn(List.of(history));
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of(stakeholder));
        when(dealService.getActivitiesByDealId(DEAL_ID)).thenReturn(List.of(activity));
        when(dealService.getNotesByDealId(DEAL_ID)).thenReturn(List.of(note));
        when(dealService.getTasksByDealId(DEAL_ID)).thenReturn(List.of(task));
        when(scoringService.scoreContacts(WORKSPACE_ID, Set.of(PERSON_ID))).thenReturn(List.of(temperature));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);
        String serialized = serialized(assembly.prompt());

        assertTrue(serialized.contains("{{P1}}"));
        assertTrue(serialized.contains("{{C1}}"));
        assertTrue(serialized.contains(MaskingEngine.OMITTED_BY_POLICY));
        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("Acme"));
        assertFalse(serialized.contains("mina.patel@acme.example"));
        assertFalse(serialized.contains("+1 415 555 0199"));
        assertFalse(serialized.contains("1 Market Street"));
        assertTrue(serialized.contains("2026-08-31"));
        assertTrue(serialized.contains("2026-07-01 09:00:00"));
        assertTrue(serialized.contains("2026-07-08 14:00:00"));
        assertTrue(serialized.contains("2026-07-07 16:00:00"));
        assertTrue(serialized.contains("2026-07-10"));
        verify(scoringService).scoreContacts(WORKSPACE_ID, Set.of(PERSON_ID));
        verify(dealRiskService).assessDeal(WORKSPACE_ID, DEAL_ID);
    }

    @Test
    void assemble_japaneseLocaleTranslatesOnlyJsonStringValues() {
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        try {
            String systemPrompt = assembler.assemble(WORKSPACE_ID, DEAL_ID).prompt().getSystemPrompt();

            assertTrue(systemPrompt.contains("Write every JSON string value in Japanese"));
            assertTrue(systemPrompt.contains("keep all JSON property names"));
            assertTrue(systemPrompt.contains("in English exactly as specified"));
            assertTrue(systemPrompt.contains("do not translate the keys"));
            assertTrue(systemPrompt.contains("\"sections\""));
            assertTrue(systemPrompt.contains("\"title\""));
            assertTrue(systemPrompt.contains("\"body\""));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void assemble_omitsSuspendedAndProvisionCeasedStakeholdersBeforeMasking() {
        Person suspended = person();
        suspended.setSuspendedAt(LocalDateTime.parse("2026-07-01T00:00:00"));
        Person ceased = new Person();
        ceased.setId(74);
        ceased.setName("Ceased Contact");
        ceased.setProvisionCeasedAt(LocalDateTime.parse("2026-07-02T00:00:00"));
        DealRiskFactor restrictedFactor = new DealRiskFactor(
                "stakeholder_cold", "high", Map.of("personId", 74, "person", "Ceased Contact"));
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of(
                new DealPerson(suspended, "Suspended role"),
                new DealPerson(ceased, "Ceased role")));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(new DealRiskDto(
                DEAL_ID, 0, null, "high", 50, List.of(restrictedFactor), "2026-07-09 18:30:00"));

        String serialized = serialized(assembler.assemble(WORKSPACE_ID, DEAL_ID).prompt());

        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("Ceased Contact"));
        assertFalse(serialized.contains("Suspended role"));
        assertFalse(serialized.contains("Ceased role"));
        assertFalse(serialized.contains("stakeholder_cold"));
        verify(scoringService).scoreContacts(WORKSPACE_ID, Set.of());
        verify(aiRelationshipContext, never()).appendStakeholderBackground(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assemble_omitsDealRecordsLinkedToRestrictedPeople() {
        Person ceased = new Person();
        ceased.setId(74);
        ceased.setName("Ceased Contact");
        ceased.setProvisionCeasedAt(LocalDateTime.parse("2026-07-02T00:00:00"));
        Activity activity = new Activity();
        activity.setPerson(ceased);
        activity.setSubject("Activity for Ceased Contact");
        Note note = new Note();
        note.setPerson(ceased);
        note.setTitle("Note for Ceased Contact");
        Task task = new Task();
        task.setPerson(ceased);
        task.setDescription("Task for Ceased Contact");
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        when(dealService.getPeopleByDealId(DEAL_ID))
            .thenReturn(List.of(new DealPerson(ceased, "Ceased role")));
        when(dealService.getActivitiesByDealId(DEAL_ID)).thenReturn(List.of(activity));
        when(dealService.getNotesByDealId(DEAL_ID)).thenReturn(List.of(note));
        when(dealService.getTasksByDealId(DEAL_ID)).thenReturn(List.of(task));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(74))).thenReturn(List.of(ceased));

        String serialized = serialized(assembler.assemble(WORKSPACE_ID, DEAL_ID).prompt());

        assertFalse(serialized.contains("Ceased Contact"));
        assertFalse(serialized.contains("Activity for"));
        assertFalse(serialized.contains("Note for"));
        assertFalse(serialized.contains("Task for"));
    }

    private static Deal deal() {
        Deal deal = new Deal();
        deal.setId(DEAL_ID);
        deal.setValue(125000);
        deal.setCurrency("USD");
        deal.setExpectedCloseDate("2026-08-31");
        return deal;
    }

    private static Person person() {
        Person person = new Person();
        person.setId(PERSON_ID);
        person.setName("Mina Patel");
        person.setEmail("mina.patel@acme.example");
        person.setPhone("+1 415 555 0199");
        person.setTitle("VP Procurement, 1 Market Street");
        return person;
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
