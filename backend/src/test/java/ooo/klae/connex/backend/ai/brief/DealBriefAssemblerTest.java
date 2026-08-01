package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
        activity.setId(201);
        activity.setSubject("Mina Patel met the team at Acme");
        activity.setNotes("Budget review went well");
        activity.setTimestamp("2026-07-08 14:00:00");
        Note note = new Note();
        note.setId(301);
        note.setTitle("Call background");
        note.setContent("Mina Patel discussed a diagnosis and follow-up details.");
        note.setCreatedAt("2026-07-07 16:00:00");
        Task task = new Task();
        task.setId(401);
        task.setDescription("Send Acme the revised proposal");
        task.setDueDate("2026-07-10");
        task.setCompleted(false);

        RelationshipTemperatureDto temperature = new RelationshipTemperatureDto(
                PERSON_ID, 64, "warm", "cooling", "2026-06-29 10:00:00", 10, 2,
                null, null, "test-model", Instant.EPOCH);
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
        when(aiRelationshipContext.appendStakeholderBackground(
                any(StringBuilder.class), eq(PERSON_ID), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AiRelationshipContext.SourceIdProvider sourceIds = invocation.getArgument(4);
                    sourceIds.sourceId("person", 99);
                    return false;
                });

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);
        String serialized = serialized(assembly.prompt());

        assertEquals(List.of(PERSON_ID, 99), assembly.contributorPersonIds());
        assertTrue(serialized.contains("{{P1}}"));
        assertTrue(serialized.contains("{{C1}}"));
        assertFalse(serialized.contains(MaskingEngine.OMITTED_BY_POLICY));
        assertFalse(serialized.contains("Mina Patel"));
        assertFalse(serialized.contains("Acme"));
        assertFalse(serialized.contains("mina.patel@acme.example"));
        assertFalse(serialized.contains("+1 415 555 0199"));
        assertFalse(serialized.contains("1 Market Street"));
        assertTrue(serialized.contains("2026-08-31"));
        assertTrue(serialized.contains("2026-07-01 09:00:00"));
        assertTrue(serialized.contains("2026-07-08 14:00:00"));
        assertFalse(serialized.contains("2026-07-07 16:00:00"));
        assertTrue(serialized.contains("2026-07-10"));
        assertTrue(serialized.contains("Source: deal.0"));
        assertTrue(serialized.contains("Source: person.0"));
        assertTrue(serialized.contains("Source: act.0"));
        assertFalse(serialized.contains("Source: " + DEAL_ID));
        assertFalse(serialized.contains("Source: 201"));
        assertEquals(new DealBriefSource("deal", DEAL_ID), assembly.sourceRegistry().get("deal.0"));
        assertEquals(new DealBriefSource("person", PERSON_ID), assembly.sourceRegistry().get("person.0"));
        assertEquals(new DealBriefSource("person", 99), assembly.sourceRegistry().get("person.1"));
        assertEquals(new DealBriefSource("act", 201), assembly.sourceRegistry().get("act.0"));
        assertFalse(assembly.sourceRegistry().containsValue(new DealBriefSource("note", 301)));
        assertEquals(new DealBriefSource("task", 401), assembly.sourceRegistry().get("task.0"));
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
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assemble_omitsDealRecordsLinkedToRestrictedPeople() {
        Person ceased = new Person();
        ceased.setId(74);
        ceased.setName("Ceased Contact");
        ceased.setProvisionCeasedAt(LocalDateTime.parse("2026-07-02T00:00:00"));
        Activity activity = new Activity();
        activity.setId(201);
        activity.setPerson(ceased);
        activity.setSubject("Activity for Ceased Contact");
        Note note = new Note();
        note.setId(301);
        note.setPerson(ceased);
        note.setTitle("Note for Ceased Contact");
        Task task = new Task();
        task.setId(401);
        task.setPerson(ceased);
        task.setDescription("Task for Ceased Contact");
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        when(dealService.getPeopleByDealId(DEAL_ID))
            .thenReturn(List.of(new DealPerson(ceased, "Ceased role")));
        when(dealService.getActivitiesByDealId(DEAL_ID)).thenReturn(List.of(activity));
        when(dealService.getNotesByDealId(DEAL_ID)).thenReturn(List.of(note));
        when(dealService.getTasksByDealId(DEAL_ID)).thenReturn(List.of(task));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(74))).thenReturn(List.of(ceased));

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);
        String serialized = serialized(assembly.prompt());

        assertEquals(List.of(), assembly.contributorPersonIds());
        assertFalse(serialized.contains("Ceased Contact"));
        assertFalse(serialized.contains("Activity for"));
        assertFalse(serialized.contains("Note for"));
        assertFalse(serialized.contains("Task for"));
    }

    @Test
    void assemble_carriesProcessablePersonLinkedOnlyThroughDealRecords() {
        Person linked = new Person();
        linked.setId(74);
        linked.setName("Linked Contact");
        Activity activity = new Activity();
        activity.setId(201);
        activity.setPerson(linked);
        activity.setSubject("Linked activity");
        Note note = new Note();
        note.setId(301);
        note.setPerson(linked);
        note.setTitle("Linked note");
        Task task = new Task();
        task.setId(401);
        task.setPerson(linked);
        task.setDescription("Linked task");
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        when(dealService.getActivitiesByDealId(DEAL_ID)).thenReturn(List.of(activity));
        when(dealService.getNotesByDealId(DEAL_ID)).thenReturn(List.of(note));
        when(dealService.getTasksByDealId(DEAL_ID)).thenReturn(List.of(task));

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);

        assertEquals(List.of(74), assembly.contributorPersonIds());
    }

    @Test
    void assemble_carriesOnlyPeopleWhoseRecordsActuallyEnterThePrompt() {
        Person stakeholder = person(10, "Stakeholder");
        Person blankStakeholder = person(11, "   ");
        Person restricted = person(99, "Restricted candidate");
        restricted.setSuspendedAt(LocalDateTime.parse("2026-07-01T00:00:00"));
        List<Activity> activities = List.of(
                activity(restricted, "Restricted activity"),
                activity(person(20, "Activity one person"), "Activity one"),
                activity(person(21, "Blank activity person"), "   "),
                activity(person(22, "Activity three person"), "Activity three"),
                activity(person(23, "Activity four person"), "Activity four"),
                activity(person(24, "Activity five person"), "Activity five"),
                activity(person(25, "Activity six person"), "Activity six"));
        List<Note> notes = List.of(
                note(person(30, "Note one person"), "Note one"),
                note(person(31, "Blank note person"), "   "),
                note(person(32, "Note three person"), "Note three"),
                note(person(33, "Note four person"), "Note four"),
                note(person(34, "Note five person"), "Note five"),
                note(person(35, "Note six person"), "Note six"));
        List<Task> tasks = List.of(
                task(restricted, "Restricted task", false),
                task(person(40, "Completed task person"), "Completed task", true),
                task(person(41, "Blank task person"), "   ", false),
                task(person(42, "Task one person"), "Task one", false),
                task(person(43, "Task two person"), "Task two", false),
                task(person(44, "Task three person"), "Task three", false),
                task(person(45, "Task four person"), "Task four", false),
                task(person(46, "Task five person"), "Task five", false),
                task(person(47, "Task six person"), "Task six", false));
        when(dealService.getDealById(DEAL_ID)).thenReturn(deal());
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of(
                new DealPerson(stakeholder, "Champion"),
                new DealPerson(blankStakeholder, "Unknown"),
                new DealPerson(restricted, "Restricted")));
        when(dealService.getActivitiesByDealId(DEAL_ID)).thenReturn(activities);
        when(dealService.getNotesByDealId(DEAL_ID)).thenReturn(notes);
        when(dealService.getTasksByDealId(DEAL_ID)).thenReturn(tasks);
        when(personMapper.getByIds(eq(WORKSPACE_ID), anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            List<Person> visible = new ArrayList<>();
            for (Object value : ids) {
                if (value instanceof Integer id) {
                    visible.add(id == restricted.getId() ? restricted : person(id, "Visible " + id));
                }
            }
            return visible;
        });

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);
        String prompt = serialized(assembly.prompt());

        assertEquals(
                List.of(10, 20, 22, 23, 24, 30, 32, 33, 34, 42, 43, 44, 45, 46),
                assembly.contributorPersonIds());
        assertTrue(prompt.contains("Activity five"));
        assertFalse(prompt.contains("Activity six"));
        assertTrue(prompt.contains("Note five"));
        assertFalse(prompt.contains("Note six"));
        assertTrue(prompt.contains("Task five"));
        assertFalse(prompt.contains("Task six"));
        assertFalse(prompt.contains("Restricted activity"));
        assertFalse(prompt.contains("Restricted task"));
    }

    @Test
    void assemble_doesNotRegisterCurrentDealFromStageHistoryOutsidePromptCap() {
        Deal emptyDeal = new Deal();
        emptyDeal.setId(DEAL_ID);
        DealStageHistory oldMeaningful = new DealStageHistory();
        oldMeaningful.setStageName("Old discovery");
        List<DealStageHistory> history = new ArrayList<>();
        history.add(oldMeaningful);
        for (int index = 0; index < DealBriefAssembler.MAX_STAGE_HISTORY; index++) {
            history.add(new DealStageHistory());
        }
        when(dealService.getDealById(DEAL_ID)).thenReturn(emptyDeal);
        when(dealService.getStageHistory(DEAL_ID)).thenReturn(history);
        when(dealService.getPeopleByDealId(DEAL_ID)).thenReturn(List.of(
                new DealPerson(person(), "Champion")));

        BriefAssembly assembly = assembler.assemble(WORKSPACE_ID, DEAL_ID);
        String prompt = serialized(assembly.prompt());

        assertFalse(prompt.contains("Old discovery"));
        assertTrue(prompt.contains("TIMELINE\n- none"));
        assertFalse(assembly.sourceRegistry().containsValue(new DealBriefSource("deal", DEAL_ID)));
        assertEquals(new DealBriefSource("person", PERSON_ID), assembly.sourceRegistry().get("person.0"));
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

    private static Person person(int id, String name) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        return person;
    }

    private static Activity activity(Person person, String subject) {
        Activity activity = new Activity();
        activity.setId(person.getId() + 1_000);
        activity.setPerson(person);
        activity.setSubject(subject);
        return activity;
    }

    private static Note note(Person person, String title) {
        Note note = new Note();
        note.setId(person.getId() + 2_000);
        note.setPerson(person);
        note.setTitle(title);
        return note;
    }

    private static Task task(Person person, String description, boolean completed) {
        Task task = new Task();
        task.setId(person.getId() + 3_000);
        task.setPerson(person);
        task.setDescription(description);
        task.setCompleted(completed);
        return task;
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
