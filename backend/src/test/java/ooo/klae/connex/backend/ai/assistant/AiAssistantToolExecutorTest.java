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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.SearchResultsDto;
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
        executor = new AiAssistantToolExecutor(
                new AiAssistantToolCatalog(), searchService, personService, companyService,
                dealService, activityService, taskService, scoringService, workspaceService);
    }

    @Test
    void unknownHandleFailsBeforeAnyRecordServiceCall() throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();

        AiAssistantLoopException failure = assertThrows(
                AiAssistantLoopException.class,
                () -> executor.execute(
                        "get_record", objectMapper.readTree("{\"handle\":\"r9\"}"), resources));

        assertEquals("unknown_handle", failure.detailReason());
        verifyNoInteractions(
                searchService, personService, companyService, dealService,
                activityService, taskService, scoringService, workspaceService);
    }

    @Test
    void reservedKeysFailClosedWithoutNestedOrInventedReads() throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 7);
        resources.register("deal", 8);

        AiAssistantLoopException schedule = assertThrows(
                AiAssistantLoopException.class,
                () -> executor.execute(
                        "find_schedule_conflicts",
                        objectMapper.readTree(
                                "{\"handle\":\"r1\",\"start\":\"2026-08-10T09:00:00Z\","
                                        + "\"end\":\"2026-08-10T10:00:00Z\"}"),
                        resources));
        AiAssistantLoopException brief = assertThrows(
                AiAssistantLoopException.class,
                () -> executor.execute(
                        "get_deal_brief", objectMapper.readTree("{\"handle\":\"r2\"}"), resources));

        assertEquals("schedule_conflicts_unavailable", schedule.detailReason());
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
                resources);
        assertEquals(List.of(), search.data().get("records"));
        assertEquals(
                List.of(),
                executor.pageContext(
                        List.of(new AiChatPageContextDto("person", 17)), resources)
                        .data().get("records"));
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\"}"), resources));
        clearInvocations(activityService, taskService);
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "list_activities",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"), resources));
        assertThrows(AiAssistantLoopException.class, () -> executor.execute(
                "list_tasks",
                objectMapper.readTree("{\"handle\":\"r1\",\"limit\":5}"), resources));

        verify(activityService, never()).getActivitiesByPersonId(17);
        verify(taskService, never()).getTasksByPersonId(17);
    }
}
