package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
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

class AiAssistantWriteToolServiceTest {
    private static final ValidatorFactory VALIDATORS =
            Validation.buildDefaultValidatorFactory();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-03-06T15:00:00Z"), ZoneOffset.UTC);
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AiChatMapper chatMapper;
    private WorkspaceService workspaceService;
    private ActivityService activityService;
    private PersonService personService;
    private CompanyService companyService;
    private DealService dealService;
    private TaskService taskService;
    private NoteService noteService;
    private TagService tagService;
    private PipelineService pipelineService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiWorkspaceGovernanceService governanceService;
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
        activityService = mock(ActivityService.class);
        personService = mock(PersonService.class);
        companyService = mock(CompanyService.class);
        dealService = mock(DealService.class);
        taskService = mock(TaskService.class);
        noteService = mock(NoteService.class);
        tagService = mock(TagService.class);
        pipelineService = mock(PipelineService.class);
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
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(true);
        AiAssistantDateResolver dateResolver = new AiAssistantDateResolver(authService, CLOCK);
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        PersonMapper personMapper = mock(PersonMapper.class);
        when(personMapper.getPersonById(TURN.workspaceId(), 31))
                .thenReturn(person(31));
        when(personMapper.getByIds(TURN.workspaceId(), List.of(31)))
                .thenReturn(List.of(person(31)));
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
                personMapper,
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                dateResolver);
        service = new AiAssistantWriteToolService(
                catalog,
                readExecutor,
                dateResolver,
                chatMapper,
                workspaceService,
                activityService,
                taskService,
                noteService,
                tagService,
                personService,
                companyService,
                dealService,
                pipelineService,
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
        storedToolCall.setWorkspaceId(TURN.workspaceId());
        storedToolCall.setMessageId(TURN.userMessageId());
        storedToolCall.setSessionId(TURN.sessionId());
        storedToolCall.setRequestedByUserId(TURN.userId());
        storedToolCall.setStatus("proposed");
        when(chatMapper.getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29)).thenReturn(storedToolCall);
        when(chatMapper.getToolCallBySession(
                TURN.workspaceId(), TURN.sessionId(), 29)).thenReturn(storedToolCall);
        when(chatMapper.updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("executed"), any(), eq(TURN.userId()))).thenReturn(1);
        when(chatMapper.updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), any(), eq(TURN.userId()))).thenReturn(1);
    }

    @Test
    void meetingExecutesImmediatelyReportsConflictAndUndoesWhileUnchanged() throws Exception {
        Person person = person(31);
        when(personService.getPersonById(31)).thenReturn(person);
        List<Activity> conflicts = IntStream.range(0, 25)
                .mapToObj(index -> activity(
                        88 + index,
                        "Existing meeting " + index,
                        "2026-03-12 13:30:00"))
                .toList();
        when(activityService.getActivitiesByPersonIdInWindow(
                eq(31), any(), any(), eq(101))).thenReturn(conflicts);
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\","
                        + "\"subject\":\"Planning\",\"start\":\"9:00am next Thursday\","
                        + "\"duration_minutes\":60}",
                "person",
                31);
        stored(write, 29);

        AtomicReference<AiAssistantToolResult> guardedResult = new AtomicReference<>();
        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, guardedResult::set);

        assertEquals(
                "2026-03-12 13:00:00",
                execution.toolCall().result().path("start").asString());
        assertEquals(
                "America/New_York",
                execution.toolCall().result().path("timezone").asString());
        assertEquals(20, execution.toolCall().result().path("conflicts").size());
        assertTrue(execution.toolCall().result().path("conflictsTruncated").asBoolean());
        assertEquals(
                20,
                ((Map<?, ?>) execution.toolResult().data().get("outcome"))
                        .get("conflictCount"));
        assertEquals(execution.toolResult(), guardedResult.get());
        assertEquals("executed", execution.toolResult().data().get("status"));
        assertTrue(execution.toolCall().undoAvailable());
        verify(activityService).getActivitiesByPersonIdInWindow(
                eq(31), any(), any(), eq(101));
        verify(activityService).create(any(Activity.class));

        doAnswer(invocation -> {
            java.util.function.Predicate<Activity> guard = invocation.getArgument(1);
            if (!guard.test(activity(73, "Planning", "2026-03-12 13:00:00"))) {
                throw new ConflictException("changed");
            }
            return null;
        }).when(activityService).deleteIf(eq(73), any());
        storedToolCall.setResultJson(objectMapper.writeValueAsString(
                objectMapper.readTree(capturedResultJson())));

        assertEquals("undone", service.undo(TURN.sessionId(), 29).status());
        verify(activityService).deleteIf(eq(73), any());
    }

    @Test
    void undoRefusesAfterThirdPartyModification() throws Exception {
        Person person = person(31);
        when(personService.getPersonById(31)).thenReturn(person);
        when(activityService.getActivitiesByPersonIdInWindow(
                eq(31), any(), any(), eq(101))).thenReturn(List.of());
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\","
                        + "\"subject\":\"Planning\",\"start\":\"9:00am next Thursday\"}",
                "person",
                31);
        stored(write, 29);
        service.executeAuto(TURN, 29, result -> { });
        storedToolCall.setResultJson(capturedResultJson());
        doAnswer(invocation -> {
            java.util.function.Predicate<Activity> guard = invocation.getArgument(1);
            if (!guard.test(activity(
                    73, "Changed by colleague", "2026-03-12 13:00:00"))) {
                throw new ConflictException("changed");
            }
            return null;
        }).when(activityService).deleteIf(eq(73), any());

        assertThrows(ConflictException.class, () -> service.undo(TURN.sessionId(), 29));

        verify(chatMapper, never()).updateExecutedToolResult(
                eq(TURN.workspaceId()), eq(29), any(), eq(TURN.userId()));
    }

    @Test
    void taskCreateDelegatesToTheNativeService() throws Exception {
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
                "deal",
                44);
        stored(write, 29);

        assertEquals("task", service.executeAuto(TURN, 29, result -> { })
                .toolCall().result().path("recordType").asString());
        verify(taskService).create(any(Task.class));
    }

    @Test
    void noteCreateDelegatesToTheNativeService() throws Exception {
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            created.setVisibility("workspace");
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Shared follow-up\","
                        + "\"visibility\":\"workspace\"}",
                "person",
                31);
        stored(write, 29);

        assertEquals("note", service.executeAuto(TURN, 29, result -> { })
                .toolCall().result().path("recordType").asString());
        verify(noteService).create(any(Note.class));
    }

    @Test
    void addTagDelegatesToTheRecordNativeServiceWithoutAdvertisingUnsafeUndo() throws Exception {
        Tag tag = new Tag();
        tag.setId(9);
        tag.setName("Priority");
        when(tagService.getAllTags()).thenReturn(List.of(tag));
        when(personService.addTag(31, 9)).thenReturn(true);
        AiAssistantPreparedWrite write = prepared(
                "add_tag",
                "{\"handle\":\"r1\",\"tag\":\"Priority\"}",
                "person",
                31);
        stored(write, 29);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });

        assertFalse(execution.toolCall().undoAvailable());
        verify(personService).addTag(31, 9);
    }

    @Test
    void tagUndoRefusesWhenAnotherTransactionOwnedTheConditionalInsert() throws Exception {
        Tag tag = new Tag();
        tag.setId(9);
        tag.setName("Priority");
        when(tagService.getAllTags()).thenReturn(List.of(tag));
        when(personService.addTag(31, 9)).thenReturn(false);
        AiAssistantPreparedWrite write = prepared(
                "add_tag",
                "{\"handle\":\"r1\",\"tag\":\"Priority\"}",
                "person",
                31);
        stored(write, 29);

        AiAssistantWriteToolService.WriteExecution execution =
                service.executeAuto(TURN, 29, result -> { });
        storedToolCall.setResultJson(capturedResultJson());

        assertFalse(execution.toolCall().undoAvailable());
        assertThrows(ConflictException.class, () -> service.undo(TURN.sessionId(), 29));
        verify(personService, never()).removeTagIfUnchanged(31, 9);
    }

    @Test
    void autoWriteLocksTheDomainRowBeforeRetainingTheRestrictionFence() throws Exception {
        Person person = person(31);
        when(personService.getPersonById(31)).thenReturn(person);
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            created.setVisibility("workspace");
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Shared follow-up\","
                        + "\"visibility\":\"workspace\"}",
                "person",
                31);
        stored(write, 29);

        service.executeAuto(TURN, 29, result -> { });

        InOrder order = inOrder(personService, restrictionEpoch);
        order.verify(personService).lockProcessablePersonForUpdate(31);
        order.verify(restrictionEpoch)
                .retainReadFenceUntilTransactionCompletionIfCurrent(
                        TURN.workspaceId(), TURN.restrictionEpoch());
    }

    @Test
    void confirmTierNeverExecutesBeforeApprovalAndDoubleApprovalIsIdempotent() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal",
                44);
        stored(write, 29);
        AiAssistantToolProposal proposal = new AiAssistantToolProposal(29, "proposed", null, true);

        assertEquals(
                "approval_required",
                service.proposalResult(write, proposal).data().get("status"));
        verify(dealService, never()).changeStage(
                any(DealService.LockedStageChange.class));

        Deal deal = new Deal();
        deal.setId(44);
        deal.setPipelineId(5);
        Stage stage = new Stage();
        stage.setId(6);
        stage.setName("Proposal");
        ooo.klae.connex.backend.beans.Pipeline pipeline =
                new ooo.klae.connex.backend.beans.Pipeline();
        pipeline.setId(5);
        stage.setPipeline(pipeline);
        DealService.LockedStageChange lockedStageChange = mock(
                DealService.LockedStageChange.class);
        when(dealService.getDealById(44)).thenReturn(deal);
        when(pipelineService.getAllStages()).thenReturn(List.of(stage));
        when(dealService.lockStageChangeRowsForUpdate(44, 6))
                .thenReturn(lockedStageChange);
        when(dealService.changeStage(lockedStageChange)).thenReturn(deal);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());
        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());
        InOrder order = inOrder(dealService, restrictionEpoch);
        order.verify(dealService).lockStageChangeRowsForUpdate(44, 6);
        order.verify(restrictionEpoch)
                .retainReadFenceUntilTransactionCompletionIfCurrent(
                        TURN.workspaceId(), TURN.restrictionEpoch());
        order.verify(dealService).changeStage(lockedStageChange);
        verify(dealService, times(1)).changeStage(lockedStageChange);
    }

    @Test
    void permissionRevokedAfterProposalBlocksApprovalExecution() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal",
                44);
        stored(write, 29);
        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .requirePermission(TURN.workspaceId(), TURN.userId(), Permission.DEAL_UPDATE);

        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        verify(dealService, never()).changeStage(
                any(DealService.LockedStageChange.class));
    }

    @Test
    void approvalRefusesWhenThePersistedProposalRestrictionEpochAdvanced() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "person",
                31);
        stored(write, 29);
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        InOrder order = inOrder(personService, restrictionEpoch);
        order.verify(personService).lockProcessablePersonForUpdate(31);
        order.verify(restrictionEpoch)
                .retainReadFenceUntilTransactionCompletionIfCurrent(
                        TURN.workspaceId(), TURN.restrictionEpoch());
        verify(personService, never()).updateOwner(31, 21);
    }

    @Test
    void approvalRefusesARestrictedTargetEvenWhenThePersistedEpochStillMatches() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "person",
                31);
        stored(write, 29);
        when(personService.lockProcessablePersonForUpdate(31))
                .thenThrow(new ResourceNotFoundException("Person not found"));

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), 29));

        verify(restrictionEpoch, never())
                .retainReadFenceUntilTransactionCompletionIfCurrent(
                        TURN.workspaceId(), TURN.restrictionEpoch());
        verify(personService, never()).updateOwner(31, 21);
    }

    @Test
    void ownerAssignmentExecutesOnlyThroughTheNativeRecordServiceAfterApproval() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company",
                52);
        stored(write, 29);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(companyService).updateOwner(52, 21);
        verify(personService, never()).updateOwner(52, 21);
        verify(dealService, never()).updateOwner(52, 21);

        InOrder lockOrder = inOrder(workspaceService, chatMapper);
        lockOrder.verify(workspaceService)
                .lockAndRequireMember(TURN.workspaceId(), TURN.userId());
        lockOrder.verify(workspaceService).lockAndRequireMember(TURN.workspaceId(), 21);
        lockOrder.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
    }

    @Test
    void identicalRequestsFromTwoTurnsBothExecute() throws Exception {
        Person person = person(31);
        when(personService.getPersonById(31)).thenReturn(person);
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            created.setVisibility("workspace");
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Same request\","
                        + "\"visibility\":\"workspace\"}",
                "person",
                31);
        stored(write, 29);
        service.executeAuto(TURN, 29, result -> { });

        AiChatQueuedTurn secondTurn = new AiChatQueuedTurn(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId(), 18, 20, 2,
                TURN.restrictionEpoch(), TURN.includePrivateNotes(), List.of(), List.of());
        AiChatTurn secondStoredTurn = new AiChatTurn();
        secondStoredTurn.setId(secondTurn.turnId());
        secondStoredTurn.setRequestedByUserId(secondTurn.userId());
        secondStoredTurn.setStatus("running");
        AiChatToolCall secondToolCall = new AiChatToolCall();
        secondToolCall.setId(30);
        secondToolCall.setWorkspaceId(secondTurn.workspaceId());
        secondToolCall.setMessageId(secondTurn.userMessageId());
        secondToolCall.setSessionId(secondTurn.sessionId());
        secondToolCall.setRequestedByUserId(secondTurn.userId());
        secondToolCall.setToolName(write.toolName());
        secondToolCall.setStatus("proposed");
        secondToolCall.setArgumentsJson(write.argumentsJson());
        secondToolCall.setIdempotencyKey("turn-18-step-1");
        when(chatMapper.getTurnByIdForUpdate(
                secondTurn.workspaceId(), secondTurn.sessionId(), secondTurn.turnId()))
                .thenReturn(secondStoredTurn);
        when(chatMapper.getToolCallBySessionForUpdate(
                secondTurn.workspaceId(), secondTurn.sessionId(), 30))
                .thenReturn(secondToolCall);
        when(chatMapper.updateToolCall(
                eq(secondTurn.workspaceId()), eq(secondTurn.userMessageId()), eq(30),
                eq("executed"), any(), eq(secondTurn.userId()))).thenReturn(1);

        service.executeAuto(secondTurn, 30, result -> { });

        verify(noteService, times(2)).create(any(Note.class));
    }

    @Test
    void firstExecutionCommitsThenReplaySkipsTheGuardWithoutRepeatingTheMutation()
            throws Exception {
        when(personService.getPersonById(31)).thenReturn(person(31));
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            created.setVisibility("workspace");
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantPreparedWrite write = prepared(
                "create_note",
                "{\"handle\":\"r1\",\"content\":\"Same request\","
                        + "\"visibility\":\"workspace\"}",
                "person",
                31);
        stored(write, 29);
        AtomicBoolean firstExecutionGuarded = new AtomicBoolean();
        AiAssistantWriteToolService.WriteExecution firstExecution = service.executeAuto(
                TURN,
                29,
                result -> firstExecutionGuarded.set(true));
        AtomicBoolean replayGuarded = new AtomicBoolean();

        AiAssistantWriteToolService.WriteExecution replay = service.executeAuto(
                TURN,
                29,
                result -> {
                    replayGuarded.set(true);
                    throw new AiAssistantLoopException(
                            "tool_result_budget_exhausted",
                            "tool_result_budget_exhausted");
                });

        assertTrue(firstExecutionGuarded.get());
        assertFalse(firstExecution.replayed());
        assertFalse(replayGuarded.get());
        assertTrue(replay.replayed());
        assertEquals("executed", replay.toolResult().data().get("status"));
        verify(noteService).create(any(Note.class));
        verify(chatMapper).updateToolCall(
                eq(TURN.workspaceId()),
                eq(TURN.userMessageId()),
                eq(29),
                eq("executed"),
                any(),
                eq(TURN.userId()));
    }

    @Test
    void proposalReadsReturnOnlyAuthorizedResolvedArguments() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        Company company = new Company();
        company.setId(52);
        company.setName("Analytical Engines");
        when(companyService.getCompanyById(52)).thenReturn(company);
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company",
                52);
        stored(write, 29);
        when(chatMapper.listPendingToolCallsBySession(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of(storedToolCall));

        var listed = service.listPendingProposals(TURN.sessionId());
        var detail = service.getPendingProposal(TURN.sessionId(), 29);

        assertEquals(1, listed.size());
        assertEquals("Analytical Engines", detail.target().name());
        assertEquals("Grace Hopper", detail.arguments().get("owner").asString());
        assertFalse(detail.arguments().has("handle"));
        assertFalse(detail.arguments().has("idempotency_key"));
    }

    @Test
    void proposalReadsRefuseOtherTenantAndNonParticipantCallers() {
        when(chatMapper.getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(null);
        assertThrows(
                ResourceNotFoundException.class,
                () -> service.listPendingProposals(TURN.sessionId()));

        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);
        when(workspaceService.getCurrentUserId()).thenReturn(77);
        when(chatMapper.getAccessibleSessionById(99, 77, TURN.sessionId())).thenReturn(null);
        assertThrows(
                ResourceNotFoundException.class,
                () -> service.getPendingProposal(TURN.sessionId(), 29));
    }

    @Test
    void otherTenantAndUnauthorizedActorsFailBeforeDomainExecution() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);
        when(workspaceService.getCurrentUserId()).thenReturn(77);
        when(chatMapper.getSessionByIdForUpdate(99, 77, TURN.sessionId())).thenReturn(null);

        assertThrows(
                ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), 29));

        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .lockAndRequireMember(99, 77);
        assertThrows(ResourceNotFoundException.class, () -> service.reject(TURN.sessionId(), 29));
        verify(dealService, never()).changeStage(
                any(DealService.LockedStageChange.class));
    }

    @Test
    void lifecycleTeardownBeforeToolDecisionBlocksEveryToolDecision() {
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.executeAuto(TURN, 29, result -> { }));
        assertThrows(ResourceNotFoundException.class, () -> service.approve(TURN.sessionId(), 29));
        assertThrows(ResourceNotFoundException.class, () -> service.reject(TURN.sessionId(), 29));
        assertThrows(ResourceNotFoundException.class, () -> service.undo(TURN.sessionId(), 29));

        verify(chatMapper, never()).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29);
    }

    @Test
    void governanceDisableBeforeToolDecisionBlocksToolMutations() {
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(false);

        assertThrows(
                ForbiddenException.class,
                () -> service.executeAuto(TURN, 29, result -> { }));
        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        verify(chatMapper, never()).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29);
    }

    private AiAssistantPreparedWrite prepared(
            String tool, String json, String targetKind, int targetId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        return service.prepare(
                tool, objectMapper.readTree(json), resources, TURN.restrictionEpoch());
    }

    private void stored(AiAssistantPreparedWrite write, int id) {
        storedToolCall.setId(id);
        storedToolCall.setToolName(write.toolName());
        storedToolCall.setArgumentsJson(write.argumentsJson());
        storedToolCall.setIdempotencyKey("turn-" + TURN.turnId() + "-step-1");
    }

    private String capturedResultJson() {
        org.mockito.ArgumentCaptor<String> result =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chatMapper).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("executed"), result.capture(), eq(TURN.userId()));
        return result.getValue();
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        person.setName("Ada Lovelace");
        return person;
    }

    private static Activity activity(int id, String subject, String timestamp) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setType("meeting");
        activity.setSubject(subject);
        activity.setTimestamp(timestamp);
        Person person = person(31);
        activity.setPerson(person);
        return activity;
    }
}
