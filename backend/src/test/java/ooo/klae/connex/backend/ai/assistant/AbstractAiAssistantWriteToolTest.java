package ooo.klae.connex.backend.ai.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.LeadResponseSlaService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SearchService;
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the write framework over mocked domain services for the tests that drive it through its
 * declared tools, with one stored tool call (29) in one private session of one running turn.
 */
abstract class AbstractAiAssistantWriteToolTest {
    static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-06T15:00:00Z"), ZoneOffset.UTC);
    static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());
    static final int TOOL_CALL_ID = 29;

    final ObjectMapper objectMapper = JsonMapper.builder().build();
    AiChatMapper chatMapper;
    WorkspaceService workspaceService;
    ActivityService activityService;
    NoteService noteService;
    PersonService personService;
    CompanyService companyService;
    DealService dealService;
    TaskService taskService;
    TagService tagService;
    PipelineService pipelineService;
    LeadResponseSlaService leadResponseSlaService;
    PersonMapper executorPersonMapper;
    AiRestrictionEpoch restrictionEpoch;
    AiWorkspaceGovernanceService governanceService;
    WorkspaceService.LockedPermissionSnapshot authority;
    AiAssistantToolCatalog catalog;
    AiAssistantDateResolver dateResolver;
    AiAssistantToolExecutor readExecutor;
    AiChatToolCall storedToolCall;

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setUpFramework() {
        chatMapper = mock(AiChatMapper.class);
        workspaceService = mock(WorkspaceService.class);
        activityService = mock(ActivityService.class);
        noteService = mock(NoteService.class);
        personService = mock(PersonService.class);
        companyService = mock(CompanyService.class);
        dealService = mock(DealService.class);
        taskService = mock(TaskService.class);
        tagService = mock(TagService.class);
        pipelineService = mock(PipelineService.class);
        leadResponseSlaService = mock(LeadResponseSlaService.class);
        executorPersonMapper = mock(PersonMapper.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        governanceService = mock(AiWorkspaceGovernanceService.class);
        AuthService authService = mock(AuthService.class);
        User actor = new User();
        actor.setId(TURN.userId());
        actor.setTimezone("America/New_York");
        when(authService.getCurrentUser()).thenReturn(actor);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(workspaceService.getCurrentUserId()).thenReturn(TURN.userId());
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(true);
        authority = mock(WorkspaceService.LockedPermissionSnapshot.class);
        when(authority.effectiveFor(TURN.userId())).thenReturn(EnumSet.allOf(Permission.class));
        when(workspaceService.lockAndRequirePermissionsSnapshot(anyInt(), any()))
                .thenReturn(authority);
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(true);
        dateResolver = new AiAssistantDateResolver(authService, CLOCK);
        catalog = new AiAssistantToolCatalog();
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setVisibility("private");
        session.setStatus("active");
        AiChatTurn turn = new AiChatTurn();
        turn.setId(TURN.turnId());
        turn.setRequestedByUserId(TURN.userId());
        turn.setStatus("running");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(turn);
        storedToolCall = new AiChatToolCall();
        storedToolCall.setId(TOOL_CALL_ID);
        storedToolCall.setWorkspaceId(TURN.workspaceId());
        storedToolCall.setMessageId(TURN.userMessageId());
        storedToolCall.setSessionId(TURN.sessionId());
        storedToolCall.setRequestedByUserId(TURN.userId());
        storedToolCall.setStatus("proposed");
        storedToolCall.setIdempotencyKey("turn-" + TURN.turnId() + "-step-1");
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        when(chatMapper.getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TOOL_CALL_ID)).thenReturn(storedToolCall);
        when(chatMapper.getToolCallBySession(
                TURN.workspaceId(), TURN.sessionId(), TOOL_CALL_ID)).thenReturn(storedToolCall);
        when(chatMapper.updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(TOOL_CALL_ID),
                any(), any(), eq(TURN.userId()))).thenReturn(1);
        when(chatMapper.updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(TOOL_CALL_ID), any(), eq(TURN.userId())))
                .thenReturn(1);
    }

    /** @return the framework over every declared tool this phase ships */
    AiAssistantWriteToolService service() {
        return service(List.of(createTaskTool(), stageTool()));
    }

    /**
     * @param tools the task and stage tools under test, possibly overridden
     * @return the framework over those tools plus the activity, note, both tag, the owner and the
     *     response deadline tools
     */
    AiAssistantWriteToolService service(List<AiAssistantWriteTool> tools) {
        List<AiAssistantWriteTool> declared = new ArrayList<>(tools);
        declared.add(createActivityTool());
        declared.add(createNoteTool());
        declared.add(addTagTool());
        declared.add(removeTagTool());
        declared.add(assignOwnerTool());
        declared.add(setResponseDueTool());
        return framework(declared);
    }

    /**
     * @param tools exactly the declared tools the framework is built over
     * @return the framework over those tools
     */
    AiAssistantWriteToolService framework(List<AiAssistantWriteTool> tools) {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, tools);
        readExecutor = new AiAssistantToolExecutor(
                catalog,
                registry,
                mock(SearchService.class),
                personService,
                companyService,
                dealService,
                activityService,
                taskService,
                mock(AiAssistantHistoryService.class),
                mock(ScoringService.class),
                workspaceService,
                executorPersonMapper,
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                dateResolver,
                mock(AiAssistantScopeReadService.class));
        return new AiAssistantWriteToolService(
                catalog,
                registry,
                readExecutor,
                chatMapper,
                workspaceService,
                taskService,
                personService,
                companyService,
                dealService,
                restrictionEpoch,
                governanceService,
                objectMapper,
                VALIDATORS.getValidator(),
                CLOCK);
    }

    AiAssistantCreateTaskWriteTool createTaskTool() {
        return new AiAssistantCreateTaskWriteTool(taskService, dateResolver, objectMapper);
    }

    AiAssistantChangeDealStageWriteTool stageTool() {
        return new AiAssistantChangeDealStageWriteTool(dealService, pipelineService);
    }

    AiAssistantCreateActivityWriteTool createActivityTool() {
        return new AiAssistantCreateActivityWriteTool(activityService, dateResolver, objectMapper);
    }

    AiAssistantCreateNoteWriteTool createNoteTool() {
        return new AiAssistantCreateNoteWriteTool(noteService, objectMapper);
    }

    AiAssistantAddTagWriteTool addTagTool() {
        return new AiAssistantAddTagWriteTool(
                tagService, personService, companyService, dealService);
    }

    AiAssistantRemoveTagWriteTool removeTagTool() {
        return new AiAssistantRemoveTagWriteTool(
                tagService, personService, companyService, dealService);
    }

    AiAssistantAssignOwnerWriteTool assignOwnerTool() {
        return new AiAssistantAssignOwnerWriteTool(personService, companyService, dealService);
    }

    AiAssistantSetResponseDueWriteTool setResponseDueTool() {
        return new AiAssistantSetResponseDueWriteTool(leadResponseSlaService);
    }

    /** Prepares and stores one proposal as tool call 29. */
    AiAssistantPreparedWrite propose(
            AiAssistantWriteToolService service,
            String tool,
            String json,
            String targetKind,
            int targetId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        AiAssistantPreparedWrite write = service.prepare(
                tool, objectMapper.readTree(json), resources, TURN.restrictionEpoch());
        storedToolCall.setToolName(write.toolName());
        storedToolCall.setArgumentsJson(write.argumentsJson());
        return write;
    }

    /** The task service returns the task it was given with id 74, as the real one does. */
    void createdTasksGetId74() {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            created.setStatus("todo");
            return created;
        }).when(taskService).create(any(Task.class));
    }

    /** The activity service returns the activity it was given with id 73, as the real one does. */
    void createdActivitiesGetId73() {
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
    }

    /** The note service returns the note it was given with id 75, as the real one does. */
    void createdNotesGetId75() {
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            return created;
        }).when(noteService).create(any(Note.class));
    }

    /** Person 31 is processable, so the executor's schedule read reaches its calendar. */
    void person31IsProcessable() {
        Person person = new Person();
        person.setId(31);
        person.setName("Ada Lovelace");
        when(executorPersonMapper.getPersonById(TURN.workspaceId(), 31)).thenReturn(person);
    }

    /**
     * Deal 44 sits in stage 5 of pipeline 3, whose stage 6 is "Proposal"; the stage-change lock
     * toward stage 6 reports a row last written before the proposal.
     */
    DealService.LockedStageChange stubStageChange() {
        Deal deal = deal(5);
        when(dealService.getDealById(44)).thenReturn(deal);
        when(pipelineService.getAllStages()).thenReturn(List.of(
                stage(5, "Qualified"), stage(6, "Proposal")));
        DealService.LockedStageChange locked = mock(DealService.LockedStageChange.class);
        when(locked.targetUpdatedAt()).thenReturn("2026-03-06 14:00:00.000000");
        when(dealService.lockStageChangeRowsForUpdate(44, 6)).thenReturn(locked);
        return locked;
    }

    String capturedExecutedResult() {
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(TOOL_CALL_ID),
                eq("executed"), result.capture(), eq(TURN.userId()));
        return result.getValue();
    }

    static Deal deal(Integer stageId) {
        Deal deal = new Deal();
        deal.setId(44);
        deal.setName("Acme renewal");
        deal.setPipelineId(3);
        deal.setStageId(stageId);
        return deal;
    }

    static Stage stage(int id, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(3);
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        stage.setPipeline(pipeline);
        return stage;
    }
}
