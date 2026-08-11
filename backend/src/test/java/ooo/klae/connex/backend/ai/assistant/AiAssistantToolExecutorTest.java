package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
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
                List.of(), List.of(), List.of(), List.of(), List.of()));
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
}
