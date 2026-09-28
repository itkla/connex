package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
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
 * Pins every durable, API and model-visible byte the tools moved onto the write-tool SPI emit.
 *
 * <p>The expected strings were captured from the per-tool switch arms before each tool moved —
 * {@code create_task} and {@code change_deal_stage} first, then {@code create_activity}, with its
 * meeting schedule-conflict enrichment, and {@code create_note} — so
 * a green run after the move is evidence that the move changed none of them: the stored proposal,
 * the stored result envelope with its outcome and inverse key order, the approval, rejection and
 * undo responses, the model's own view of the outcome, and the transcript cards. A key that
 * drifts, a value that is re-derived differently, or a new sibling such as a divergence record
 * appearing on an ordinary write turns this red where no behavioural test would notice.
 */
class AiAssistantWriteEnvelopeEquivalenceTest {
    private static final ValidatorFactory VALIDATORS =
            Validation.buildDefaultValidatorFactory();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-03-06T15:00:00Z"), ZoneOffset.UTC);
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());
    private static final String TASK_ARGUMENTS = "{\"tool\":\"create_task\",\"tier\":\"auto\","
            + "\"restrictionEpoch\":23,\"target\":{\"kind\":\"person\",\"id\":31},"
            + "\"request\":{\"handle\":\"r1\",\"description\":\"Prepare agenda\","
            + "\"due_date\":\"2026-03-12\"}}";
    private static final String TASK_STATE = "{\"description\":\"Prepare agenda\","
            + "\"completed\":false,\"status\":\"todo\",\"position\":0,"
            + "\"dueDate\":\"2026-03-12\",\"assignedToId\":11,\"personId\":31,\"dealId\":0}";
    private static final String STAGE_ARGUMENTS = "{\"tool\":\"change_deal_stage\","
            + "\"tier\":\"confirm\",\"restrictionEpoch\":23,"
            + "\"target\":{\"kind\":\"deal\",\"id\":44},"
            + "\"request\":{\"handle\":\"r1\",\"stage\":\"proposal \"}}";
    private static final String MEETING_ARGUMENTS = "{\"tool\":\"create_activity\","
            + "\"tier\":\"auto\",\"restrictionEpoch\":23,"
            + "\"target\":{\"kind\":\"person\",\"id\":31},"
            + "\"request\":{\"handle\":\"r1\",\"type\":\"meeting\",\"subject\":\"Planning\","
            + "\"notes\":\"Bring the deck\",\"start\":\"9:00am next Thursday\","
            + "\"duration_minutes\":45}}";
    private static final String MEETING_STATE = "{\"type\":\"meeting\",\"subject\":\"Planning\","
            + "\"notes\":\"Bring the deck\",\"timestamp\":\"2026-03-12 13:00:00\","
            + "\"personId\":31,\"dealId\":0}";
    private static final String MEETING_OUTCOME = "{\"status\":\"executed\","
            + "\"recordType\":\"activity\",\"type\":\"meeting\",\"subject\":\"Planning\","
            + "\"start\":\"2026-03-12 13:00:00\",\"timezone\":\"America/New_York\","
            + "\"conflicts\":[{\"type\":\"meeting\",\"subject\":\"Board review\","
            + "\"notes\":\"Quarterly numbers\",\"timestamp\":\"2026-03-12 13:15:00\"},"
            + "{\"type\":\"call\",\"subject\":\"Pipeline sync\","
            + "\"timestamp\":\"2026-03-12 13:30:00\"}],\"conflictsTruncated\":false}";
    private static final String NOTE_ARGUMENTS = "{\"tool\":\"create_note\",\"tier\":\"auto\","
            + "\"restrictionEpoch\":23,\"target\":{\"kind\":\"deal\",\"id\":44},"
            + "\"request\":{\"handle\":\"r1\",\"content\":\"Shared follow-up\","
            + "\"title\":\"Follow-up\",\"visibility\":\"workspace\"}}";
    private static final String NOTE_STATE = "{\"content\":\"Shared follow-up\","
            + "\"title\":\"Follow-up\",\"visibility\":\"workspace\",\"personId\":0,"
            + "\"dealId\":44}";
    private static final String NOTE_OUTCOME = "{\"status\":\"executed\","
            + "\"recordType\":\"note\",\"title\":\"Follow-up\",\"visibility\":\"workspace\"}";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AiChatMapper chatMapper;
    private WorkspaceService workspaceService;
    private PersonService personService;
    private DealService dealService;
    private TaskService taskService;
    private PipelineService pipelineService;
    private ActivityService activityService;
    private NoteService noteService;
    private PersonMapper executorPersonMapper;
    private WorkspaceService.LockedPermissionSnapshot authority;
    private AiAssistantWriteToolService service;
    private AiChatToolCall storedToolCall;

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        workspaceService = mock(WorkspaceService.class);
        personService = mock(PersonService.class);
        dealService = mock(DealService.class);
        taskService = mock(TaskService.class);
        pipelineService = mock(PipelineService.class);
        activityService = mock(ActivityService.class);
        noteService = mock(NoteService.class);
        executorPersonMapper = mock(PersonMapper.class);
        AiRestrictionEpoch restrictionEpoch = mock(AiRestrictionEpoch.class);
        AiWorkspaceGovernanceService governanceService = mock(AiWorkspaceGovernanceService.class);
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
        AiAssistantDateResolver dateResolver = new AiAssistantDateResolver(authService, CLOCK);
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        CompanyService companyService = mock(CompanyService.class);
        AiAssistantToolExecutor readExecutor = new AiAssistantToolExecutor(
                catalog,
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
        service = new AiAssistantWriteToolService(
                catalog,
                new AiAssistantWriteToolRegistry(catalog, List.of(
                        new AiAssistantCreateTaskWriteTool(taskService, dateResolver, objectMapper),
                        new AiAssistantChangeDealStageWriteTool(dealService, pipelineService),
                        new AiAssistantCreateActivityWriteTool(
                                activityService, dateResolver, objectMapper),
                        new AiAssistantCreateNoteWriteTool(noteService, objectMapper))),
                readExecutor,
                dateResolver,
                chatMapper,
                workspaceService,
                activityService,
                taskService,
                noteService,
                mock(TagService.class),
                personService,
                companyService,
                dealService,
                restrictionEpoch,
                governanceService,
                objectMapper,
                VALIDATORS.getValidator(),
                CLOCK);
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
        storedToolCall.setId(29);
        storedToolCall.setWorkspaceId(TURN.workspaceId());
        storedToolCall.setMessageId(TURN.userMessageId());
        storedToolCall.setSessionId(TURN.sessionId());
        storedToolCall.setRequestedByUserId(TURN.userId());
        storedToolCall.setStatus("proposed");
        storedToolCall.setIdempotencyKey("turn-" + TURN.turnId() + "-step-1");
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        when(chatMapper.getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29)).thenReturn(storedToolCall);
        when(chatMapper.getToolCallBySession(
                TURN.workspaceId(), TURN.sessionId(), 29)).thenReturn(storedToolCall);
        when(chatMapper.updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                any(), any(), eq(TURN.userId()))).thenReturn(1);
        when(chatMapper.updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), any(), eq(TURN.userId()))).thenReturn(1);
    }

    @Test
    void anImmediateTaskKeepsEveryDurableApiAndModelByte() throws Exception {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            created.setStatus("todo");
            return created;
        }).when(taskService).create(any(Task.class));
        AiAssistantPreparedWrite write = prepared(
                "create_task",
                "{\"handle\":\"r1\",\"description\":\"Prepare agenda\","
                        + "\"due_date\":\"2026-03-12\"}",
                "person",
                31);

        assertEquals(TASK_ARGUMENTS, write.argumentsJson());
        stored(write);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        String resultJson = capturedExecutedResult();
        String expectedResult = "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"task\",\"description\":\"Prepare agenda\","
                + "\"dueDate\":\"2026-03-12\"},\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"task\","
                + "\"entityId\":74,\"fingerprint\":\"" + sha256(TASK_STATE) + "\"}}";
        assertEquals(expectedResult, resultJson);
        assertEquals(
                "{\"id\":29,\"tool\":\"create_task\",\"tier\":\"auto\",\"status\":\"executed\","
                        + "\"result\":{\"status\":\"executed\",\"recordType\":\"task\","
                        + "\"description\":\"Prepare agenda\",\"dueDate\":\"2026-03-12\"},"
                        + "\"undoAvailable\":true,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(execution.toolCall()));
        String modelView = "{\"toolCallId\":29,\"tool\":\"create_task\",\"tier\":\"auto\","
                + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"task\","
                + "\"description\":\"Prepare agenda\",\"dueDate\":\"2026-03-12\"}}";
        assertEquals(modelView, objectMapper.writeValueAsString(execution.toolResult().data()));
        assertFalse(resultJson.contains("verification"));

        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(resultJson);
        AiAssistantWriteToolService.WriteExecution replay =
                service.executeAuto(TURN, 29, result -> { });
        assertEquals(modelView, objectMapper.writeValueAsString(replay.toolResult().data()));
        assertEquals(
                modelView,
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(29, "executed", resultJson, true)).data()));

        doAnswer(invocation -> {
            Predicate<Task> guard = invocation.getArgument(1);
            Task current = new Task();
            current.setId(74);
            current.setDescription("Prepare agenda");
            current.setStatus("todo");
            current.setDueDate("2026-03-12");
            User assignee = new User();
            assignee.setId(TURN.userId());
            current.setAssignedTo(assignee);
            Person linked = new Person();
            linked.setId(31);
            current.setPerson(linked);
            if (!guard.test(current)) {
                throw new ConflictException("changed");
            }
            return null;
        }).when(taskService).deleteIf(eq(74), any());

        assertEquals(
                "{\"id\":29,\"tool\":\"create_task\",\"tier\":\"auto\",\"status\":\"undone\","
                        + "\"result\":{\"status\":\"executed\",\"recordType\":\"task\","
                        + "\"description\":\"Prepare agenda\",\"dueDate\":\"2026-03-12\"},"
                        + "\"undoAvailable\":false,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(service.undo(TURN.sessionId(), 29)));
        ArgumentCaptor<String> undone = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), undone.capture(), eq(TURN.userId()));
        assertEquals(
                expectedResult.replace("\"status\":\"available\"", "\"status\":\"undone\"")
                        .replace("\"}}", "\",\"undoneAt\":\"2026-03-06T15:00:00Z\"}}"),
                undone.getValue());
    }

    @Test
    void anApprovedStageChangeKeepsEveryDurableApiAndModelByte() throws Exception {
        stubStage();
        Deal changed = deal();
        changed.setStageId(6);
        changed.setClosedAt("2026-03-06 15:00:00");
        when(dealService.changeStage(any(DealService.LockedStageChange.class)))
                .thenReturn(changed);
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"proposal \"}",
                "deal",
                44);

        assertEquals(STAGE_ARGUMENTS, write.argumentsJson());
        stored(write);
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                        + "\"status\":\"approval_required\",\"outcome\":{}}",
                objectMapper.writeValueAsString(service.proposalResult(
                        write, new AiAssistantToolProposal(29, "proposed", null, true)).data()));

        String approved = objectMapper.writeValueAsString(
                service.approve(TURN.sessionId(), 29));

        String resultJson = capturedExecutedResult();
        assertEquals(
                "{\"tier\":\"confirm\",\"approval\":{\"status\":\"approved\","
                        + "\"at\":\"2026-03-06T15:00:00Z\"},\"outcome\":{\"status\":\"executed\","
                        + "\"recordType\":\"deal\",\"stage\":\"Proposal\","
                        + "\"closedAt\":\"2026-03-06 15:00:00\"}}",
                resultJson);
        assertEquals(
                "{\"id\":29,\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                        + "\"status\":\"executed\",\"result\":{\"status\":\"executed\","
                        + "\"recordType\":\"deal\",\"stage\":\"Proposal\","
                        + "\"closedAt\":\"2026-03-06 15:00:00\"},\"undoAvailable\":false,"
                        + "\"undoExpiresAt\":null}",
                approved);
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                        + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"deal\","
                        + "\"stage\":\"Proposal\"}}",
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(29, "executed", resultJson, true)).data()));
    }

    @Test
    void aRejectedStageChangeKeepsItsResponseAndStoredEnvelope() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"proposal \"}",
                "deal",
                44);
        stored(write);

        assertEquals(
                "{\"id\":29,\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                        + "\"status\":\"rejected\",\"result\":{},\"undoAvailable\":false,"
                        + "\"undoExpiresAt\":null}",
                objectMapper.writeValueAsString(service.reject(TURN.sessionId(), 29)));
        ArgumentCaptor<String> rejected = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("rejected"), rejected.capture(), eq(TURN.userId()));
        assertEquals(
                "{\"tier\":\"confirm\",\"approval\":{\"status\":\"rejected\","
                        + "\"at\":\"2026-03-06T15:00:00Z\"}}",
                rejected.getValue());
    }

    @Test
    void theTranscriptCardsForBothToolsKeepEveryByte() throws Exception {
        AiChatMapper readChatMapper = mock(AiChatMapper.class);
        WorkspaceService readWorkspace = mock(WorkspaceService.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        PipelineMapper pipelineMapper = mock(PipelineMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        when(readWorkspace.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(readWorkspace.getCurrentUserId()).thenReturn(TURN.userId());
        when(readWorkspace.permissionsFor(TURN.workspaceId(), TURN.userId()))
                .thenReturn(EnumSet.allOf(Permission.class));
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setStatus("active");
        when(readChatMapper.getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        String taskResult = "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"task\",\"description\":\"Prepare agenda\","
                + "\"dueDate\":\"2026-03-12\"},\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"task\","
                + "\"entityId\":74,\"fingerprint\":\"f\"}}";
        List<AiChatToolCall> cards = List.of(
                card(41, TURN.userId(), "executed", TASK_ARGUMENTS, taskResult),
                card(42, TURN.userId(), "executed", TASK_ARGUMENTS,
                        taskResult.replace("\"available\"", "\"undone\"")),
                card(43, 99, "executed", TASK_ARGUMENTS, taskResult),
                card(44, TURN.userId(), "proposed", STAGE_ARGUMENTS, null),
                card(45, 99, "proposed", STAGE_ARGUMENTS, null),
                card(46, TURN.userId(), "executed", STAGE_ARGUMENTS,
                        "{\"tier\":\"confirm\",\"approval\":{\"status\":\"approved\","
                                + "\"at\":\"2026-03-06T15:00:00Z\"},\"outcome\":{"
                                + "\"status\":\"executed\",\"recordType\":\"deal\","
                                + "\"stage\":\"Proposal\"}}"),
                card(47, TURN.userId(), "rejected", STAGE_ARGUMENTS,
                        "{\"tier\":\"confirm\",\"approval\":{\"status\":\"rejected\","
                                + "\"at\":\"2026-03-06T15:00:00Z\"}}"),
                card(48, TURN.userId(), "failed", STAGE_ARGUMENTS, null));
        when(readChatMapper.listToolCallsBySession(
                TURN.workspaceId(), TURN.sessionId(), false, 100)).thenReturn(cards);
        when(readChatMapper.listAssistantMessagesBySessionAndTurnIds(
                TURN.workspaceId(), TURN.sessionId(), List.of(TURN.turnId()), 100))
                .thenReturn(List.of());
        Person person = new Person();
        person.setId(31);
        person.setName("Ada Lovelace");
        when(personMapper.getByIds(TURN.workspaceId(), List.of(31))).thenReturn(List.of(person));
        Deal deal = deal();
        deal.setStageId(5);
        deal.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(dealMapper.getByIds(TURN.workspaceId(), List.of(44))).thenReturn(List.of(deal));
        when(pipelineMapper.getAllStages(TURN.workspaceId())).thenReturn(List.of(
                stage(5, "Qualified"), stage(6, "Proposal")));
        when(taskMapper.getVisibleIdsIn(TURN.workspaceId(), List.of(74))).thenReturn(List.of(74));
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        AiAssistantToolCallReadService readService = new AiAssistantToolCallReadService(
                catalog,
                new AiAssistantWriteToolRegistry(catalog, List.of(
                        new AiAssistantCreateTaskWriteTool(
                                taskService, mock(AiAssistantDateResolver.class), objectMapper),
                        new AiAssistantChangeDealStageWriteTool(dealService, pipelineService),
                        new AiAssistantCreateActivityWriteTool(
                                activityService, mock(AiAssistantDateResolver.class), objectMapper),
                        new AiAssistantCreateNoteWriteTool(noteService, objectMapper))),
                readChatMapper,
                readWorkspace,
                personMapper,
                mock(CompanyMapper.class),
                dealMapper,
                pipelineMapper,
                mock(ActivityMapper.class),
                taskMapper,
                mock(NoteMapper.class),
                mock(AiAssistantSessionReadAudit.class),
                objectMapper,
                CLOCK);

        assertEquals(
                "[" + String.join(",", List.of(
                        "{\"id\":41,\"toolName\":\"create_task\",\"tier\":\"auto\","
                                + "\"status\":\"executed\",\"target\":{\"kind\":\"person\","
                                + "\"id\":31,\"label\":\"Ada Lovelace\"},"
                                + "\"requestSummary\":\"Create a task\","
                                + "\"outcomeSummary\":\"Task created\",\"change\":null,"
                                + "\"outcomeValues\":[{\"field\":\"description\","
                                + "\"value\":\"Prepare agenda\"},{\"field\":\"dueDate\","
                                + "\"value\":\"2026-03-12\"}],"
                                + "\"createdRecord\":{\"kind\":\"task\",\"id\":74},"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":true," + TIMES + "}",
                        "{\"id\":42,\"toolName\":\"create_task\",\"tier\":\"auto\","
                                + "\"status\":\"undone\",\"target\":{\"kind\":\"person\","
                                + "\"id\":31,\"label\":\"Ada Lovelace\"},"
                                + "\"requestSummary\":\"Create a task\","
                                + "\"outcomeSummary\":\"Created record removed\","
                                + "\"change\":null,\"outcomeValues\":[],"
                                + "\"createdRecord\":null,\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":43,\"toolName\":\"create_task\",\"tier\":\"auto\","
                                + "\"status\":\"executed\",\"target\":{\"kind\":\"person\","
                                + "\"id\":31,\"label\":\"Ada Lovelace\"},"
                                + "\"requestSummary\":\"Create a task\","
                                + "\"outcomeSummary\":\"Task created\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":44,\"toolName\":\"change_deal_stage\",\"tier\":\"confirm\","
                                + "\"status\":\"proposed\",\"target\":{\"kind\":\"deal\","
                                + "\"id\":44,\"label\":\"Acme renewal\"},"
                                + "\"requestSummary\":\"Change deal stage to: Proposal\","
                                + "\"outcomeSummary\":null,\"change\":{\"field\":\"stage\","
                                + "\"currentValue\":\"Qualified\","
                                + "\"currentValueUnresolved\":false,"
                                + "\"proposedValue\":\"Proposal\",\"state\":\"ready\"},"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":45,\"toolName\":\"change_deal_stage\",\"tier\":\"confirm\","
                                + "\"status\":\"proposed\",\"target\":{\"kind\":\"deal\","
                                + "\"id\":44,\"label\":\"Acme renewal\"},"
                                + "\"requestSummary\":\"Change the deal stage\","
                                + "\"outcomeSummary\":null,\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":46,\"toolName\":\"change_deal_stage\",\"tier\":\"confirm\","
                                + "\"status\":\"executed\",\"target\":{\"kind\":\"deal\","
                                + "\"id\":44,\"label\":\"Acme renewal\"},"
                                + "\"requestSummary\":\"Change deal stage to: Proposal\","
                                + "\"outcomeSummary\":\"Deal stage changed\",\"change\":null,"
                                + "\"outcomeValues\":[{\"field\":\"stage\","
                                + "\"value\":\"Proposal\"}],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":47,\"toolName\":\"change_deal_stage\",\"tier\":\"confirm\","
                                + "\"status\":\"rejected\",\"target\":{\"kind\":\"deal\","
                                + "\"id\":44,\"label\":\"Acme renewal\"},"
                                + "\"requestSummary\":\"Change deal stage to: Proposal\","
                                + "\"outcomeSummary\":\"Request rejected\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":48,\"toolName\":\"change_deal_stage\",\"tier\":\"confirm\","
                                + "\"status\":\"failed\",\"target\":{\"kind\":\"deal\","
                                + "\"id\":44,\"label\":\"Acme renewal\"},"
                                + "\"requestSummary\":\"Change deal stage to: Proposal\","
                                + "\"outcomeSummary\":\"Request failed\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}")) + "]",
                objectMapper.writeValueAsString(readService.list(TURN.sessionId(), false)));
    }

    @Test
    void anImmediateMeetingKeepsEveryDurableApiAndModelByte() throws Exception {
        stubConflictSearch(List.of(
                conflict(88, "meeting", "Board review", "Quarterly numbers", "2026-03-12 13:15:00"),
                conflict(89, "call", "Pipeline sync", null, "2026-03-12 13:30:00")));
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\",\"subject\":\"Planning\","
                        + "\"notes\":\"Bring the deck\",\"start\":\"9:00am next Thursday\","
                        + "\"duration_minutes\":45}",
                "person",
                31);

        assertEquals(MEETING_ARGUMENTS, write.argumentsJson());
        stored(write);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        verify(activityService).getActivitiesByPersonIdInWindow(
                31,
                LocalDateTime.of(2026, 3, 12, 13, 0),
                LocalDateTime.of(2026, 3, 12, 13, 45),
                101);
        String resultJson = capturedExecutedResult();
        String expectedResult = "{\"tier\":\"auto\",\"outcome\":" + MEETING_OUTCOME
                + ",\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"activity\","
                + "\"entityId\":73,\"fingerprint\":\"" + sha256(MEETING_STATE) + "\"}}";
        assertEquals(expectedResult, resultJson);
        assertEquals(
                "{\"id\":29,\"tool\":\"create_activity\",\"tier\":\"auto\","
                        + "\"status\":\"executed\",\"result\":" + MEETING_OUTCOME + ","
                        + "\"undoAvailable\":true,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(execution.toolCall()));
        String modelView = "{\"toolCallId\":29,\"tool\":\"create_activity\",\"tier\":\"auto\","
                + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"activity\","
                + "\"type\":\"meeting\",\"subject\":\"Planning\","
                + "\"start\":\"2026-03-12 13:00:00\",\"timezone\":\"America/New_York\","
                + "\"conflictCount\":2,\"conflictsTruncated\":false}}";
        assertEquals(modelView, objectMapper.writeValueAsString(execution.toolResult().data()));
        assertFalse(resultJson.contains("verification"));

        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(resultJson);
        assertEquals(
                modelView,
                objectMapper.writeValueAsString(
                        service.executeAuto(TURN, 29, result -> { }).toolResult().data()));
        assertEquals(
                modelView,
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(29, "executed", resultJson, true)).data()));

        doAnswer(invocation -> {
            Predicate<Activity> guard = invocation.getArgument(1);
            Activity current = new Activity();
            current.setId(73);
            current.setType("meeting");
            current.setSubject("Planning");
            current.setNotes("Bring the deck");
            current.setTimestamp("2026-03-12 13:00:00");
            current.setPerson(person(31));
            if (!guard.test(current)) {
                throw new ConflictException("changed");
            }
            return null;
        }).when(activityService).deleteIf(eq(73), any());

        assertEquals(
                "{\"id\":29,\"tool\":\"create_activity\",\"tier\":\"auto\","
                        + "\"status\":\"undone\",\"result\":" + MEETING_OUTCOME + ","
                        + "\"undoAvailable\":false,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(service.undo(TURN.sessionId(), 29)));
        ArgumentCaptor<String> undone = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), undone.capture(), eq(TURN.userId()));
        assertEquals(
                "{\"tier\":\"auto\",\"outcome\":" + MEETING_OUTCOME
                        + ",\"undo\":{\"status\":\"undone\","
                        + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"activity\","
                        + "\"entityId\":73,\"fingerprint\":\"" + sha256(MEETING_STATE) + "\","
                        + "\"undoneAt\":\"2026-03-06T15:00:00Z\"}}",
                undone.getValue());
    }

    @Test
    void anImmediateCallOnADealSearchesNoScheduleAndKeepsItsBytes() throws Exception {
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(76);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"call\",\"subject\":\"Renewal check-in\","
                        + "\"start\":\"9:00am next Thursday\"}",
                "deal",
                44);

        assertEquals(
                "{\"tool\":\"create_activity\",\"tier\":\"auto\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"deal\",\"id\":44},"
                        + "\"request\":{\"handle\":\"r1\",\"type\":\"call\","
                        + "\"subject\":\"Renewal check-in\",\"notes\":null,"
                        + "\"start\":\"9:00am next Thursday\",\"duration_minutes\":null}}",
                write.argumentsJson());
        stored(write);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        verify(activityService, never()).getActivitiesByPersonIdInWindow(
                anyInt(), any(), any(), anyInt());
        assertEquals(
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"recordType\":\"activity\",\"type\":\"call\","
                        + "\"subject\":\"Renewal check-in\",\"start\":\"2026-03-12 13:00:00\","
                        + "\"timezone\":\"America/New_York\",\"conflicts\":[],"
                        + "\"conflictsTruncated\":false},\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"activity\","
                        + "\"entityId\":76,\"fingerprint\":\"" + sha256(
                                "{\"type\":\"call\",\"subject\":\"Renewal check-in\","
                                        + "\"notes\":null,\"timestamp\":\"2026-03-12 13:00:00\","
                                        + "\"personId\":0,\"dealId\":44}") + "\"}}",
                capturedExecutedResult());
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"create_activity\",\"tier\":\"auto\","
                        + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"activity\","
                        + "\"type\":\"call\",\"subject\":\"Renewal check-in\","
                        + "\"start\":\"2026-03-12 13:00:00\",\"timezone\":\"America/New_York\","
                        + "\"conflictCount\":0,\"conflictsTruncated\":false}}",
                objectMapper.writeValueAsString(execution.toolResult().data()));
    }

    @Test
    void anImmediateNoteKeepsEveryDurableApiAndModelByte() throws Exception {
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Shared follow-up\",\"title\":\"Follow-up\","
                        + "\"visibility\":\"workspace\"}",
                "deal",
                44);

        assertEquals(NOTE_ARGUMENTS, write.argumentsJson());
        stored(write);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        String resultJson = capturedExecutedResult();
        String expectedResult = "{\"tier\":\"auto\",\"outcome\":" + NOTE_OUTCOME
                + ",\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"note\","
                + "\"entityId\":75,\"fingerprint\":\"" + sha256(NOTE_STATE) + "\"}}";
        assertEquals(expectedResult, resultJson);
        assertEquals(
                "{\"id\":29,\"tool\":\"create_note\",\"tier\":\"auto\","
                        + "\"status\":\"executed\",\"result\":" + NOTE_OUTCOME + ","
                        + "\"undoAvailable\":true,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(execution.toolCall()));
        String modelView = "{\"toolCallId\":29,\"tool\":\"create_note\",\"tier\":\"auto\","
                + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"note\","
                + "\"title\":\"Follow-up\",\"visibility\":\"workspace\"}}";
        assertEquals(modelView, objectMapper.writeValueAsString(execution.toolResult().data()));
        assertFalse(resultJson.contains("verification"));

        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(resultJson);
        assertEquals(
                modelView,
                objectMapper.writeValueAsString(
                        service.executeAuto(TURN, 29, result -> { }).toolResult().data()));
        assertEquals(
                modelView,
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(29, "executed", resultJson, true)).data()));

        doAnswer(invocation -> {
            Predicate<Note> guard = invocation.getArgument(1);
            Note current = new Note();
            current.setId(75);
            current.setContent("Shared follow-up");
            current.setTitle("Follow-up");
            current.setVisibility("workspace");
            current.setDeal(deal());
            if (!guard.test(current)) {
                throw new ConflictException("changed");
            }
            return null;
        }).when(noteService).deleteIf(eq(75), any());

        assertEquals(
                "{\"id\":29,\"tool\":\"create_note\",\"tier\":\"auto\","
                        + "\"status\":\"undone\",\"result\":" + NOTE_OUTCOME + ","
                        + "\"undoAvailable\":false,\"undoExpiresAt\":\"2026-03-06T15:10:00Z\"}",
                objectMapper.writeValueAsString(service.undo(TURN.sessionId(), 29)));
        ArgumentCaptor<String> undone = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), undone.capture(), eq(TURN.userId()));
        assertEquals(
                "{\"tier\":\"auto\",\"outcome\":" + NOTE_OUTCOME
                        + ",\"undo\":{\"status\":\"undone\","
                        + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"note\","
                        + "\"entityId\":75,\"fingerprint\":\"" + sha256(NOTE_STATE) + "\","
                        + "\"undoneAt\":\"2026-03-06T15:00:00Z\"}}",
                undone.getValue());
    }

    @Test
    void anUntitledPrivateNoteKeepsItsBytes() throws Exception {
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(77);
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Private reminder\","
                        + "\"visibility\":\"private\"}",
                "person",
                31);

        assertEquals(
                "{\"tool\":\"create_note\",\"tier\":\"auto\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"person\",\"id\":31},"
                        + "\"request\":{\"handle\":\"r1\",\"content\":\"Private reminder\","
                        + "\"title\":null,\"visibility\":\"private\"}}",
                write.argumentsJson());
        stored(write);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        assertEquals(
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"recordType\":\"note\",\"visibility\":\"private\"},"
                        + "\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"note\","
                        + "\"entityId\":77,\"fingerprint\":\"" + sha256(
                                "{\"content\":\"Private reminder\",\"title\":null,"
                                        + "\"visibility\":\"private\",\"personId\":31,"
                                        + "\"dealId\":0}") + "\"}}",
                capturedExecutedResult());
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"create_note\",\"tier\":\"auto\","
                        + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"note\","
                        + "\"visibility\":\"private\"}}",
                objectMapper.writeValueAsString(execution.toolResult().data()));
    }

    @Test
    void theTranscriptCardsForActivitiesAndNotesKeepEveryByte() throws Exception {
        AiChatMapper readChatMapper = mock(AiChatMapper.class);
        WorkspaceService readWorkspace = mock(WorkspaceService.class);
        PersonMapper readPersonMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        when(readWorkspace.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(readWorkspace.getCurrentUserId()).thenReturn(TURN.userId());
        when(readWorkspace.permissionsFor(TURN.workspaceId(), TURN.userId()))
                .thenReturn(EnumSet.allOf(Permission.class));
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setStatus("active");
        when(readChatMapper.getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        String meetingResult = "{\"tier\":\"auto\",\"outcome\":" + MEETING_OUTCOME
                + ",\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"activity\","
                + "\"entityId\":73,\"fingerprint\":\"f\"}}";
        String noteResult = "{\"tier\":\"auto\",\"outcome\":" + NOTE_OUTCOME
                + ",\"undo\":{\"status\":\"available\","
                + "\"expiresAt\":\"2026-03-06T15:10:00Z\",\"entityKind\":\"note\","
                + "\"entityId\":75,\"fingerprint\":\"f\"}}";
        when(readChatMapper.listToolCallsBySession(
                TURN.workspaceId(), TURN.sessionId(), false, 100)).thenReturn(List.of(
                card(41, TURN.userId(), "executed", "create_activity",
                        MEETING_ARGUMENTS, meetingResult),
                card(42, TURN.userId(), "executed", "create_activity", MEETING_ARGUMENTS,
                        meetingResult.replace("\"available\"", "\"undone\"")),
                card(43, 99, "executed", "create_activity", MEETING_ARGUMENTS, meetingResult),
                card(44, TURN.userId(), "failed", "create_activity", MEETING_ARGUMENTS, null),
                card(45, TURN.userId(), "executed", "create_note", NOTE_ARGUMENTS, noteResult),
                card(46, TURN.userId(), "executed", "create_note", NOTE_ARGUMENTS,
                        noteResult.replace("\"available\"", "\"undone\"")),
                card(47, 99, "executed", "create_note", NOTE_ARGUMENTS, noteResult)));
        when(readChatMapper.listAssistantMessagesBySessionAndTurnIds(
                TURN.workspaceId(), TURN.sessionId(), List.of(TURN.turnId()), 100))
                .thenReturn(List.of());
        when(readPersonMapper.getByIds(TURN.workspaceId(), List.of(31)))
                .thenReturn(List.of(person(31)));
        when(dealMapper.getByIds(TURN.workspaceId(), List.of(44))).thenReturn(List.of(deal()));
        when(activityMapper.getVisibleIdsIn(TURN.workspaceId(), List.of(73)))
                .thenReturn(List.of(73));
        when(noteMapper.getVisibleNoteIdsIn(TURN.workspaceId(), List.of(75), TURN.userId()))
                .thenReturn(List.of(75));
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        AiAssistantToolCallReadService readService = new AiAssistantToolCallReadService(
                catalog,
                new AiAssistantWriteToolRegistry(catalog, List.of(
                        new AiAssistantCreateTaskWriteTool(
                                taskService, mock(AiAssistantDateResolver.class), objectMapper),
                        new AiAssistantChangeDealStageWriteTool(dealService, pipelineService),
                        new AiAssistantCreateActivityWriteTool(
                                activityService, mock(AiAssistantDateResolver.class), objectMapper),
                        new AiAssistantCreateNoteWriteTool(noteService, objectMapper))),
                readChatMapper,
                readWorkspace,
                readPersonMapper,
                mock(CompanyMapper.class),
                dealMapper,
                mock(PipelineMapper.class),
                activityMapper,
                mock(TaskMapper.class),
                noteMapper,
                mock(AiAssistantSessionReadAudit.class),
                objectMapper,
                CLOCK);
        String person31 = "\"target\":{\"kind\":\"person\",\"id\":31,\"label\":\"Ada Lovelace\"},";
        String deal44 = "\"target\":{\"kind\":\"deal\",\"id\":44,\"label\":\"Acme renewal\"},";

        assertEquals(
                "[" + String.join(",", List.of(
                        "{\"id\":41,\"toolName\":\"create_activity\",\"tier\":\"auto\","
                                + "\"status\":\"executed\"," + person31
                                + "\"requestSummary\":\"Create an activity\","
                                + "\"outcomeSummary\":\"Activity created\",\"change\":null,"
                                + "\"outcomeValues\":[{\"field\":\"type\",\"value\":\"meeting\"},"
                                + "{\"field\":\"subject\",\"value\":\"Planning\"},"
                                + "{\"field\":\"start\",\"value\":\"2026-03-12 13:00:00\"}],"
                                + "\"createdRecord\":{\"kind\":\"activity\",\"id\":73},"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":true," + TIMES + "}",
                        "{\"id\":42,\"toolName\":\"create_activity\",\"tier\":\"auto\","
                                + "\"status\":\"undone\"," + person31
                                + "\"requestSummary\":\"Create an activity\","
                                + "\"outcomeSummary\":\"Created record removed\","
                                + "\"change\":null,\"outcomeValues\":[],"
                                + "\"createdRecord\":null,\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":43,\"toolName\":\"create_activity\",\"tier\":\"auto\","
                                + "\"status\":\"executed\"," + person31
                                + "\"requestSummary\":\"Create an activity\","
                                + "\"outcomeSummary\":\"Activity created\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":44,\"toolName\":\"create_activity\",\"tier\":\"auto\","
                                + "\"status\":\"failed\"," + person31
                                + "\"requestSummary\":\"Create an activity\","
                                + "\"outcomeSummary\":\"Request failed\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,\"undoExpiresAt\":null,"
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":45,\"toolName\":\"create_note\",\"tier\":\"auto\","
                                + "\"status\":\"executed\"," + deal44
                                + "\"requestSummary\":\"Create a note\","
                                + "\"outcomeSummary\":\"Note created\",\"change\":null,"
                                + "\"outcomeValues\":[{\"field\":\"title\",\"value\":\"Follow-up\"},"
                                + "{\"field\":\"visibility\",\"value\":\"workspace\"}],"
                                + "\"createdRecord\":{\"kind\":\"note\",\"id\":75},"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":true," + TIMES + "}",
                        "{\"id\":46,\"toolName\":\"create_note\",\"tier\":\"auto\","
                                + "\"status\":\"undone\"," + deal44
                                + "\"requestSummary\":\"Create a note\","
                                + "\"outcomeSummary\":\"Created record removed\","
                                + "\"change\":null,\"outcomeValues\":[],"
                                + "\"createdRecord\":null,\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}",
                        "{\"id\":47,\"toolName\":\"create_note\",\"tier\":\"auto\","
                                + "\"status\":\"executed\"," + deal44
                                + "\"requestSummary\":\"Create a note\","
                                + "\"outcomeSummary\":\"Note created\",\"change\":null,"
                                + "\"outcomeValues\":[],\"createdRecord\":null,"
                                + "\"messageId\":null,\"turnId\":17,"
                                + "\"undoExpiresAt\":\"2026-03-06T15:10:00Z\","
                                + "\"undoAvailable\":false," + TIMES + "}")) + "]",
                objectMapper.writeValueAsString(readService.list(TURN.sessionId(), false)));

        when(readWorkspace.permissionsFor(TURN.workspaceId(), TURN.userId()))
                .thenReturn(EnumSet.complementOf(
                        EnumSet.of(Permission.ACTIVITY_DELETE, Permission.NOTE_DELETE)));

        assertEquals(
                List.of(false, false, false, false, false, false, false),
                readService.list(TURN.sessionId(), false).stream()
                        .map(AiAssistantToolCallReadDto::undoAvailable)
                        .toList());
    }

    private static final String TIMES = "\"createdAt\":\"2026-03-06 14:59:00.000000\","
            + "\"updatedAt\":\"2026-03-06 15:00:00.000000\","
            + "\"executedAt\":\"2026-03-06 15:00:00.000000\"";

    private void stubStage() {
        Deal deal = deal();
        deal.setStageId(5);
        when(dealService.getDealById(44)).thenReturn(deal);
        when(pipelineService.getAllStages()).thenReturn(List.of(
                stage(5, "Qualified"), stage(6, "Proposal")));
        DealService.LockedStageChange locked = mock(DealService.LockedStageChange.class);
        when(locked.targetUpdatedAt()).thenReturn("2026-03-06 14:00:00.000000");
        when(dealService.lockStageChangeRowsForUpdate(44, 6)).thenReturn(locked);
    }

    private static Deal deal() {
        Deal deal = new Deal();
        deal.setId(44);
        deal.setName("Acme renewal");
        deal.setPipelineId(3);
        return deal;
    }

    private static Stage stage(int id, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(3);
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        stage.setPipeline(pipeline);
        return stage;
    }

    private static AiChatToolCall card(
            int id, int requestedBy, String status, String arguments, String result) {
        return card(
                id, requestedBy, status,
                arguments.contains("create_task") ? "create_task" : "change_deal_stage",
                arguments, result);
    }

    private static AiChatToolCall card(
            int id,
            int requestedBy,
            String status,
            String tool,
            String arguments,
            String result) {
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setId(id);
        toolCall.setWorkspaceId(TURN.workspaceId());
        toolCall.setMessageId(TURN.userMessageId());
        toolCall.setSessionId(TURN.sessionId());
        toolCall.setRequestedByUserId(requestedBy);
        toolCall.setToolName(tool);
        toolCall.setStatus(status);
        toolCall.setArgumentsJson(arguments);
        toolCall.setResultJson(result);
        toolCall.setIdempotencyKey("turn-" + TURN.turnId() + "-step-" + (id - 40));
        toolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        toolCall.setUpdatedAt("2026-03-06 15:00:00.000000");
        toolCall.setExecutedAt("2026-03-06 15:00:00.000000");
        return toolCall;
    }

    /** The person is processable and each candidate activity links to it, as the executor requires. */
    private void stubConflictSearch(List<Activity> candidates) {
        when(executorPersonMapper.getPersonById(TURN.workspaceId(), 31)).thenReturn(person(31));
        when(executorPersonMapper.getByIds(TURN.workspaceId(), List.of(31)))
                .thenReturn(List.of(person(31)));
        when(activityService.getActivitiesByPersonIdInWindow(
                eq(31), any(), any(), eq(101))).thenReturn(candidates);
    }

    private static Activity conflict(
            int id, String type, String subject, String notes, String timestamp) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setType(type);
        activity.setSubject(subject);
        activity.setNotes(notes);
        activity.setTimestamp(timestamp);
        activity.setPerson(person(31));
        return activity;
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        person.setName("Ada Lovelace");
        return person;
    }

    private AiAssistantPreparedWrite prepared(
            String tool, String json, String targetKind, int targetId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        return service.prepare(
                tool, objectMapper.readTree(json), resources, TURN.restrictionEpoch());
    }

    private void stored(AiAssistantPreparedWrite write) {
        storedToolCall.setToolName(write.toolName());
        storedToolCall.setArgumentsJson(write.argumentsJson());
    }

    private String capturedExecutedResult() {
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("executed"), result.capture(), eq(TURN.userId()));
        return result.getValue();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
