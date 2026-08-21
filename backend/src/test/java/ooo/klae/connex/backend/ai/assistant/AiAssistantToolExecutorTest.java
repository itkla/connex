package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SearchService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantToolExecutorTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private SearchService searchService;
    private PersonService personService;
    private CompanyService companyService;
    private DealService dealService;
    private ActivityService activityService;
    private TaskService taskService;
    private ScoringService scoringService;
    private WorkspaceService workspaceService;
    private PersonMapper personMapper;
    private CompanyMapper companyMapper;
    private DealMapper dealMapper;
    private AiAssistantDateResolver dateResolver;
    private AiAssistantToolExecutor executor;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        personService = mock(PersonService.class);
        companyService = mock(CompanyService.class);
        dealService = mock(DealService.class);
        activityService = mock(ActivityService.class);
        taskService = mock(TaskService.class);
        scoringService = mock(ScoringService.class);
        workspaceService = mock(WorkspaceService.class);
        personMapper = mock(PersonMapper.class);
        companyMapper = mock(CompanyMapper.class);
        dealMapper = mock(DealMapper.class);
        dateResolver = mock(AiAssistantDateResolver.class);
        executor = new AiAssistantToolExecutor(
                new AiAssistantToolCatalog(), searchService, personService, companyService,
                dealService, activityService, taskService, scoringService, workspaceService,
                personMapper, companyMapper, dealMapper, dateResolver);
    }

    @Test
    void unknownHandleFailsBeforeAnyRecordServiceCall() throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> executor.execute(
                        "get_record", objectMapper.readTree("{\"handle\":\"r9\"}"), resources, true));

        assertEquals("unknown_handle", failure.detailReason());
        verifyNoInteractions(
                searchService, personService, companyService, dealService,
                activityService, taskService, scoringService, workspaceService);
    }

    @Test
    void reservedDealBriefFailsClosedWithoutNestedReads() throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 7);
        resources.register("deal", 8);

        AiAssistantLoopException brief = assertThrows(
                AiAssistantLoopException.class,
                () -> executor.execute(
                        "get_deal_brief", objectMapper.readTree("{\"handle\":\"r2\"}"), resources, true));

        assertEquals("deal_brief_nested_generation_unavailable", brief.detailReason());
        verifyNoInteractions(
                searchService, personService, companyService, dealService,
                activityService, taskService, scoringService, workspaceService);
    }

    @Test
    void restrictedPersonIsInvisibleAcrossSearchContextRecordActivitiesAndTasks() throws Exception {
        Person restricted = new Person();
        restricted.setId(17);
        restricted.setName("Restricted Person");
        restricted.setSuspendedAt(LocalDateTime.parse("2026-08-01T00:00:00"));
        PersonDto restrictedSearch = PersonDto.from(restricted);
        when(searchService.search("Restricted")).thenReturn(new SearchResultsDto(
                List.of(), List.of(restrictedSearch), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(personService.getPersonById(17)).thenReturn(restricted);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 17);

        AiAssistantToolResult search = executor.execute(
                "search_records",
                objectMapper.readTree("{\"query\":\"Restricted\",\"kinds\":[\"person\"]}"),
                resources,
                true);
        assertEquals(List.of(), search.data().get("records"));
        assertEquals(
                List.of(),
                executor.pageContext(
                        List.of(new AiChatPageContextDto("person", 17)), resources)
                        .data().get("records"));
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\"}"), resources, true));
        clearInvocations(activityService, taskService);
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "list_activities",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"), resources, true));
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "list_tasks",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"), resources, true));

        verify(activityService, never()).getActivitiesByPersonId(17);
        verify(taskService, never()).getTasksByPersonId(17);
    }

    @Test
    void sharedSessionRecordReadsExcludePrivateNotesWhilePrivateSessionsKeepThem() throws Exception {
        Note workspaceNote = new Note();
        workspaceNote.setVisibility("workspace");
        workspaceNote.setContent("Visible to the workspace");
        Note privateNote = new Note();
        privateNote.setVisibility("private");
        privateNote.setContent("Owner only");
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        person.setNotes(new Note[] {workspaceNote, privateNote});
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 17);

        AiAssistantToolResult shared = executor.execute(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\"}"), resources, false);
        AiAssistantToolResult privateResult = executor.execute(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\"}"), resources, true);

        assertEquals(
                List.of(Map.of("content", "Visible to the workspace")),
                shared.data().get("notes"));
        assertEquals(
                List.of(
                        Map.of("content", "Visible to the workspace"),
                        Map.of("content", "Owner only")),
                privateResult.data().get("notes"));
    }

    @Test
    void replayContextUsesOneBoundedBatchInsteadOfHydratingEachPerson() {
        List<Integer> ids = IntStream.rangeClosed(1, 50).boxed().toList();
        List<AiChatPageContextDto> context = ids.stream()
                .map(id -> new AiChatPageContextDto("person", id))
                .toList();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(personMapper.getByIds(7, ids)).thenReturn(List.of());

        AiAssistantToolResult result = executor.pageContext(
                context, new AiChatResourceRegistry());

        assertEquals(List.of(), result.data().get("records"));
        verify(personMapper).getByIds(7, ids);
        verifyNoInteractions(personService, companyService, dealService);
    }

    @Test
    void restrictedPersonsRecordsAreFilteredThroughCompanyAndDealReads() throws Exception {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        Person restricted = new Person();
        restricted.setId(17);
        restricted.setSuspendedAt(LocalDateTime.parse("2026-08-01T00:00:00"));
        when(personMapper.getByIds(7, List.of(17))).thenReturn(List.of(restricted));
        Activity activity = new Activity();
        activity.setPerson(restricted);
        EntityReference restrictedReference = new EntityReference();
        restrictedReference.setRefType("person");
        restrictedReference.setRefId(17);
        Activity referencedActivity = new Activity();
        referencedActivity.setReferences(List.of(restrictedReference));
        Task task = new Task();
        task.setPerson(restricted);
        Note note = new Note();
        note.setReferences(List.of(restrictedReference));
        note.setVisibility("workspace");
        note.setContent("Discuss [Restricted Person](person:17)");
        Company company = new Company();
        company.setId(5);
        company.setName("Example Company");
        Deal deal = new Deal();
        deal.setId(8);
        deal.setName("Example Deal");
        when(companyService.getCompanyById(5)).thenReturn(company);
        when(companyService.getCompanyTimeline(5, 5)).thenReturn(
                new CompanyService.CompanyTimelineData(
                        List.of(activity, referencedActivity), List.of(task), List.of(note)));
        when(companyService.getCompanyTimeline(5, 10)).thenReturn(
                new CompanyService.CompanyTimelineData(
                        List.of(activity, referencedActivity), List.of(task), List.of(note)));
        when(dealService.getDealById(8)).thenReturn(deal);
        when(activityService.getActivitiesByDealId(8)).thenReturn(
                List.of(activity, referencedActivity));
        when(dealService.getNotesByDealId(8)).thenReturn(List.of(note));
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("company", 5);
        resources.register("deal", 8);

        assertEquals(List.of(), executor.execute(
                "list_activities",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"),
                resources,
                true).data().get("activities"));
        assertEquals(List.of(), executor.execute(
                "list_tasks",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"),
                resources,
                true).data().get("tasks"));
        assertEquals(List.of(), executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes"));
        assertEquals(List.of(), executor.execute(
                "list_activities",
                objectMapper.readTree("{\"handle\":\"r2\",\"limit\":5}"),
                resources,
                true).data().get("activities"));
        assertEquals(List.of(), executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r2\"}"),
                resources,
                true).data().get("notes"));
    }

    @Test
    void oversizedNotesAreTruncatedWithinFieldAndAggregateBudgets() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note[] notes = IntStream.range(0, 10)
                .mapToObj(index -> {
                    Note note = new Note();
                    note.setVisibility("workspace");
                    note.setContent("x".repeat(50_000));
                    return note;
                })
                .toArray(Note[]::new);
        person.setNotes(notes);
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        List<?> retained = (List<?>) noteData;
        assertEquals(4, retained.size());
        int totalCharacters = 0;
        for (Object value : retained) {
            Map<?, ?> note = (Map<?, ?>) value;
            String content = (String) note.get("content");
            assertEquals(4_000, content.length());
            assertEquals(true, note.get("contentTruncated"));
            totalCharacters += content.length();
        }
        assertEquals(16_000, totalCharacters);
    }

    @Test
    void noteContactDataIsRedactedBeforeTheFieldBoundaryIsApplied() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note note = new Note();
        note.setVisibility("workspace");
        note.setContent(
                "x".repeat(3_989) + " victim@example.com " + "tail".repeat(20));
        person.setNotes(new Note[] {note});
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        Map<?, ?> retained = (Map<?, ?>) ((List<?>) noteData).getFirst();
        String content = (String) retained.get("content");
        assertEquals(4_000, content.length());
        assertTrue(content.endsWith("[redacted]"));
        assertFalse(content.contains("victim"));
        assertFalse(content.contains("@example"));
        assertEquals(true, retained.get("contentTruncated"));
    }

    @Test
    void noteIdentifierIsRedactedBeforeTheFieldBoundaryIsApplied() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note note = new Note();
        note.setVisibility("workspace");
        note.setContent("x".repeat(3_988) + " Ada Lovelace " + "tail".repeat(20));
        person.setNotes(new Note[] {note});
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry(new MaskingContext());
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        Map<?, ?> retained = (Map<?, ?>) ((List<?>) noteData).getFirst();
        String content = (String) retained.get("content");
        assertEquals(4_000, content.length());
        assertTrue(content.contains("[redacted]"));
        assertFalse(content.contains("Ada"));
        assertFalse(content.contains("Lovelace"));
        assertEquals(true, retained.get("contentTruncated"));
    }

    @Test
    void noteSpecialCareTextIsScreenedBeforeTheFieldBoundaryIsApplied() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note note = new Note();
        note.setVisibility("workspace");
        note.setContent("x".repeat(3_985) + " The contact discussed a diagnosis.");
        person.setNotes(new Note[] {note});
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry(new MaskingContext());
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        Map<?, ?> retained = (Map<?, ?>) ((List<?>) noteData).getFirst();
        assertEquals("[omitted by policy]", retained.get("content"));
    }

    @Test
    void noteStructuredTimestampSurvivesPreTruncationScreening() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note note = new Note();
        note.setVisibility("workspace");
        note.setCreatedAt("2026-08-21 12:34:56");
        note.setContent("Follow up next week");
        person.setNotes(new Note[] {note});
        when(personService.getPersonById(17)).thenReturn(person);
        AiChatResourceRegistry resources = new AiChatResourceRegistry(new MaskingContext());
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        Map<?, ?> retained = (Map<?, ?>) ((List<?>) noteData).getFirst();
        assertEquals("2026-08-21 12:34:56", retained.get("createdAt"));
    }

    @Test
    void linkedPersonIdentifierIsRedactedBeforeTheNoteBoundaryIsApplied() throws Exception {
        Person linkedPerson = new Person();
        linkedPerson.setId(18);
        linkedPerson.setName("Grace Hopper");
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        Note note = new Note();
        note.setVisibility("workspace");
        note.setPerson(linkedPerson);
        note.setContent("x".repeat(3_988) + " Grace Hopper " + "tail".repeat(20));
        person.setNotes(new Note[] {note});
        when(personService.getPersonById(17)).thenReturn(person);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(personMapper.getByIds(7, List.of(18))).thenReturn(List.of(linkedPerson));
        AiChatResourceRegistry resources = new AiChatResourceRegistry(new MaskingContext());
        resources.register("person", 17);

        Object noteData = executor.execute(
                "get_record",
                objectMapper.readTree("{\"handle\":\"r1\"}"),
                resources,
                true).data().get("notes");

        Map<?, ?> retained = (Map<?, ?>) ((List<?>) noteData).getFirst();
        String content = (String) retained.get("content");
        assertEquals(4_000, content.length());
        assertTrue(content.contains("[redacted]"));
        assertFalse(content.contains("Grace"));
        assertFalse(content.contains("Hopper"));
        assertEquals(true, retained.get("contentTruncated"));
    }

    @Test
    void scheduleIdentifierIsRedactedBeforeTheFieldBoundaryIsApplied() throws Exception {
        Person person = new Person();
        person.setId(17);
        person.setName("Ada Lovelace");
        when(personService.getPersonById(17)).thenReturn(person);
        Activity activity = new Activity();
        activity.setSubject("x".repeat(500) + " Ada Lovelace " + "tail".repeat(20));
        when(activityService.getActivitiesByPersonIdInWindow(
                eq(17), any(), any(), eq(101))).thenReturn(List.of(activity));
        LocalDateTime start = LocalDateTime.parse("2026-08-11T09:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-08-11T11:00:00");
        when(dateResolver.resolveDateTime("start")).thenReturn(
                new AiAssistantDateResolver.ResolvedDateTime(
                        start, start, ZoneOffset.UTC, "2026-08-11 09:00:00"));
        when(dateResolver.resolveDateTime("end")).thenReturn(
                new AiAssistantDateResolver.ResolvedDateTime(
                        end, end, ZoneOffset.UTC, "2026-08-11 11:00:00"));
        AiChatResourceRegistry resources = new AiChatResourceRegistry(new MaskingContext());
        resources.register("person", 17);

        AiAssistantToolResult result = executor.execute(
                "find_schedule_conflicts",
                objectMapper.readTree(
                        "{\"handle\":\"r1\",\"start\":\"start\",\"end\":\"end\"}"),
                resources,
                true);

        Map<?, ?> conflict = (Map<?, ?>) ((List<?>) result.data().get("conflicts")).getFirst();
        String subject = (String) conflict.get("subject");
        assertEquals(512, subject.length());
        assertTrue(subject.contains("[redacted]"));
        assertFalse(subject.contains("Ada"));
        assertFalse(subject.contains("Lovelace"));
        assertEquals(true, conflict.get("subjectTruncated"));
    }

    @Test
    void scheduleConflictResultsBoundCountAndTextBeforePromptSerialization() {
        Person person = new Person();
        person.setId(17);
        when(personService.getPersonById(17)).thenReturn(person);
        List<Activity> activities = IntStream.range(0, 25)
                .mapToObj(index -> {
                    Activity activity = new Activity();
                    activity.setSubject("S".repeat(2_000));
                    activity.setNotes("N".repeat(2_000));
                    activity.setTimestamp("2026-08-11 10:00:00");
                    return activity;
                })
                .toList();
        when(activityService.getActivitiesByPersonIdInWindow(
                eq(17), any(), any(), eq(101))).thenReturn(activities);

        AiAssistantToolResult result = executor.findScheduleConflicts(
                17,
                LocalDateTime.parse("2026-08-11T09:00:00"),
                LocalDateTime.parse("2026-08-11T11:00:00"));

        List<?> conflicts = (List<?>) result.data().get("conflicts");
        assertEquals(20, conflicts.size());
        assertEquals(true, result.data().get("conflictsTruncated"));
        Map<?, ?> conflict = (Map<?, ?>) conflicts.getFirst();
        assertEquals(512, ((String) conflict.get("subject")).length());
        assertEquals(512, ((String) conflict.get("notes")).length());
        assertEquals("2026-08-11 10:00:00", conflict.get("timestamp"));
        assertEquals(true, conflict.get("subjectTruncated"));
        assertEquals(true, conflict.get("notesTruncated"));
        verify(activityService).getActivitiesByPersonIdInWindow(
                17,
                LocalDateTime.parse("2026-08-11T09:00:00"),
                LocalDateTime.parse("2026-08-11T11:00:00"),
                101);
    }
}
