package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private LeadResponseSlaService leadResponseSlaService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiWorkspaceGovernanceService governanceService;
    private WorkspaceService.LockedPermissionSnapshot authority;
    private AiAssistantWriteToolService service;
    private AiAssistantAddTagWriteTool addTagTool;
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
        when(personService.isOwnedByCurrentWorkspace(anyInt())).thenReturn(true);
        companyService = mock(CompanyService.class);
        dealService = mock(DealService.class);
        taskService = mock(TaskService.class);
        noteService = mock(NoteService.class);
        tagService = mock(TagService.class);
        pipelineService = mock(PipelineService.class);
        leadResponseSlaService = mock(LeadResponseSlaService.class);
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
        grantAllExcept();
        when(workspaceService.lockAndRequirePermissionsSnapshot(anyInt(), any()))
                .thenReturn(authority);
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
        addTagTool = spy(new AiAssistantAddTagWriteTool(
                tagService, personService, companyService, dealService));
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                new AiAssistantCreateTaskWriteTool(taskService, dateResolver, objectMapper),
                new AiAssistantChangeDealStageWriteTool(dealService, pipelineService),
                new AiAssistantCreateActivityWriteTool(activityService, dateResolver, objectMapper),
                new AiAssistantCreateNoteWriteTool(noteService, objectMapper),
                addTagTool,
                new AiAssistantRemoveTagWriteTool(
                        tagService, personService, companyService, dealService),
                new AiAssistantAssignOwnerWriteTool(personService, companyService, dealService),
                new AiAssistantSetResponseDueWriteTool(leadResponseSlaService)));
        AiAssistantToolExecutor readExecutor = new AiAssistantToolExecutor(
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
                personMapper,
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                dateResolver,
                mock(AiAssistantScopeReadService.class));
        service = new AiAssistantWriteToolService(
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
        Deal proposalDeal = new Deal();
        proposalDeal.setId(44);
        proposalDeal.setPipelineId(5);
        when(dealService.getDealById(44)).thenReturn(proposalDeal);
        when(pipelineService.getAllStages()).thenReturn(List.of(pipelineStage(6, "Proposal")));
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
        Map<?, ?> modelOutcome = (Map<?, ?>) execution.toolResult().data().get("outcome");
        assertEquals(20, modelOutcome.get("conflictCount"));
        assertEquals(Boolean.TRUE, modelOutcome.get("conflictsTruncated"));
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

    /**
     * A durable tag row whose undo node was forged, or written by a future version, as available
     * must still be refused by the framework's own inverse check, before the tool is asked to undo
     * anything: {@code add_tag} declares no inverse, so no tag association it created may be removed.
     */
    @Test
    void tagUndoRefusesAnAvailableUndoNodeBecauseTheToolDeclaresNoInverse() throws Exception {
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
        service.executeAuto(TURN, 29, result -> { });
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(capturedResultJson());
        ObjectNode undo = (ObjectNode) envelope.get("undo");
        undo.put("status", "available");
        undo.put("expiresAt", CLOCK.instant().plusSeconds(300).toString());
        assertEquals("tag", undo.get("entityKind").asString());
        assertEquals(9, undo.get("tagId").intValue());
        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(objectMapper.writeValueAsString(envelope));

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.undo(TURN.sessionId(), 29));

        assertEquals("Assistant tool has no owned inverse", refused.getMessage());
        verify(addTagTool, never()).undo(any(), any());
        verify(personService, never()).removeTag(anyInt(), anyInt());
        verify(personService, never()).removeTagIfUnchanged(anyInt(), anyInt());
        verify(chatMapper, never()).updateExecutedToolResult(
                anyInt(), anyInt(), anyString(), anyInt());
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
        grantAllExcept(Permission.DEAL_UPDATE);

        ForbiddenException refused = assertThrows(
                ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals(
                "Requires the DEAL_UPDATE permission in this workspace", refused.getMessage());
        verify(dealService, never()).changeStage(
                any(DealService.LockedStageChange.class));
        verify(workspaceService, never()).requirePermission(
                TURN.workspaceId(), TURN.userId(), Permission.DEAL_UPDATE);
    }

    @Test
    void autoWriteRefusesWhenTheLockedAuthorityLacksTheToolPermission() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "create_task",
                "{\"handle\":\"r1\",\"description\":\"Send the renewal deck\"}",
                "person",
                31);
        stored(write, 29);
        grantAllExcept(Permission.TASK_DELETE);

        ForbiddenException refused = assertThrows(
                ForbiddenException.class,
                () -> service.executeAuto(TURN, 29, result -> { }));

        assertEquals(
                "Requires the TASK_DELETE permission in this workspace", refused.getMessage());
        verify(taskService, never()).create(any(Task.class));
    }

    @Test
    void undoRefusesWhenTheLockedAuthorityLacksTheToolPermission() throws Exception {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            return created;
        }).when(taskService).create(any(Task.class));
        AiAssistantPreparedWrite write = prepared(
                "create_task",
                "{\"handle\":\"r1\",\"description\":\"Send the renewal deck\"}",
                "person",
                31);
        stored(write, 29);
        service.executeAuto(TURN, 29, result -> { });
        storedToolCall.setResultJson(capturedResultJson());
        grantAllExcept(Permission.TASK_DELETE);

        ForbiddenException refused = assertThrows(
                ForbiddenException.class, () -> service.undo(TURN.sessionId(), 29));

        assertEquals(
                "Requires the TASK_DELETE permission in this workspace", refused.getMessage());
        verify(taskService, never()).deleteIf(eq(74), any());
    }

    @Test
    void everyToolDecisionReadsItsAuthorityFromLockedRows() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal",
                44);
        stored(write, 29);
        doThrow(new ForbiddenException("Requires the AI_USE permission in this workspace"))
                .when(workspaceService)
                .lockAndRequirePermissionsSnapshot(eq(TURN.workspaceId()), any());

        assertThrows(
                ForbiddenException.class,
                () -> service.executeAuto(TURN, 29, result -> { }));
        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));
        assertThrows(ForbiddenException.class, () -> service.reject(TURN.sessionId(), 29));
        assertThrows(ForbiddenException.class, () -> service.undo(TURN.sessionId(), 29));

        verify(chatMapper, never()).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29);
    }

    @Test
    void approverWithoutAiUseIsRefusedByTheLockedAuthorityBeforeAnySessionLock() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal",
                44);
        stored(write, 29);
        doThrow(new ForbiddenException("Requires the AI_USE permission in this workspace"))
                .when(workspaceService)
                .lockAndRequirePermissionsSnapshot(eq(TURN.workspaceId()), any());

        ForbiddenException refused = assertThrows(
                ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Requires the AI_USE permission in this workspace", refused.getMessage());
        verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(), Map.of(TURN.userId(), Set.of(Permission.AI_USE)));
        verify(workspaceService, never()).permissionsFor(anyInt(), anyInt());
        verify(chatMapper, never()).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
    }

    @Test
    void approverWithoutAiUseLearnsAnUnresolvableOwnerBeforeTheLockedAuthorityRefusal()
            throws Exception {
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
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of());
        doThrow(new ForbiddenException("Requires the AI_USE permission in this workspace"))
                .when(workspaceService)
                .lockAndRequirePermissionsSnapshot(eq(TURN.workspaceId()), any());

        ResourceNotFoundException refused = assertThrows(
                ResourceNotFoundException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Owner is unavailable or ambiguous", refused.getMessage());
        verify(workspaceService, never()).lockAndRequirePermissionsSnapshot(anyInt(), any());
        verify(workspaceService, never()).permissionsFor(anyInt(), anyInt());
        verify(companyService, never()).updateOwner(eq(52), any());
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
    void approvalRefusesWhenTheTargetWasWrittenAfterTheProposal() throws Exception {
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
        storedToolCall.setCreatedAt("2026-03-05 12:00:00.000000");
        Person edited = person(31);
        edited.setUpdatedAt("2026-03-05 12:00:30.000000");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(edited);

        assertThrows(ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        verify(personService, never()).updateOwner(31, 21);
        verify(chatMapper, never()).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("executed"), any(), eq(TURN.userId()));
    }

    @Test
    void approvalRefusesWhenTheTargetWasWrittenInTheProposalsOwnSecond() throws Exception {
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
        storedToolCall.setCreatedAt("2026-03-05 12:00:00.400000");
        Person edited = person(31);
        edited.setUpdatedAt("2026-03-05 12:00:00");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(edited);

        assertThrows(ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        verify(personService, never()).updateOwner(31, 21);
    }

    @Test
    void approvalStillAppliesToARecordLastWrittenTheSecondBeforeTheProposal() throws Exception {
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
        storedToolCall.setCreatedAt("2026-03-05 12:00:00.400000");
        Person edited = person(31);
        edited.setUpdatedAt("2026-03-05 11:59:59");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(edited);
        Person owned = person(31);
        owned.setOwnerId(21);
        when(personService.updateOwner(31, 21)).thenReturn(owned);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(personService).updateOwner(31, 21);
    }

    @Test
    void anAutoWriteIsNeverHeldBackByARecordWrittenAfterItsOwnStep() throws Exception {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            created.setStatus("todo");
            return created;
        }).when(taskService).create(any(Task.class));
        AiAssistantPreparedWrite write = prepared(
                "create_task",
                "{\"handle\":\"r1\",\"description\":\"Send the renewal deck\"}",
                "person",
                31);
        stored(write, 29);
        storedToolCall.setCreatedAt("2026-03-05 12:00:00.000000");
        Person edited = person(31);
        edited.setUpdatedAt("2026-03-05 12:00:30.000000");
        when(personService.lockProcessablePersonForShare(31)).thenReturn(edited);
        when(personService.getPersonById(31)).thenReturn(edited);

        assertEquals(
                "executed",
                service.executeAuto(TURN, 29, result -> { }).toolResult().data().get("status"));

        InOrder order = inOrder(taskService, personService, restrictionEpoch);
        order.verify(taskService).lockBoardForCreation();
        order.verify(personService).lockProcessablePersonForShare(31);
        order.verify(restrictionEpoch).retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch());
        order.verify(taskService).create(any(Task.class));
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
        storedToolCall.setCreatedAt("2026-03-05 12:00:00.000000");
        Company unchanged = new Company();
        unchanged.setId(52);
        unchanged.setUpdatedAt("2026-03-05 11:59:00.000000");
        when(companyService.lockOwnedCompanyForUpdate(52)).thenReturn(unchanged);
        Company owned = new Company();
        owned.setId(52);
        owned.setOwnerId(21);
        when(companyService.updateOwner(52, 21)).thenReturn(owned);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(companyService).updateOwner(52, 21);
        verify(personService, never()).updateOwner(52, 21);
        verify(dealService, never()).updateOwner(52, 21);

        InOrder lockOrder = inOrder(workspaceService, chatMapper, companyService);
        lockOrder.verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(),
                Map.of(
                        TURN.userId(), Set.of(Permission.AI_USE),
                        21, Set.of()));
        lockOrder.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        lockOrder.verify(chatMapper).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29);
        lockOrder.verify(companyService).lockOwnedCompanyForUpdate(52);
        verify(workspaceService, never()).lockAndRequireMember(
                eq(TURN.workspaceId()), anyInt());
    }

    @Test
    void theOwnerWrittenIsThePrincipalResolvedAndLockedBeforeTheWrite() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        User renamedInto = new User();
        renamedInto.setId(22);
        renamedInto.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(owner))
                .thenReturn(List.of(owner))
                .thenReturn(List.of(renamedInto));
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company",
                52);
        stored(write, 29);
        Company owned = new Company();
        owned.setId(52);
        owned.setOwnerId(21);
        when(companyService.updateOwner(52, 21)).thenReturn(owned);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(workspaceService, times(2)).getMembers(TURN.workspaceId());
        InOrder order = inOrder(workspaceService, companyService);
        order.verify(workspaceService).getMembers(TURN.workspaceId());
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(),
                Map.of(TURN.userId(), Set.of(Permission.AI_USE), 21, Set.of()));
        order.verify(companyService).lockOwnedCompanyForUpdate(52);
        order.verify(companyService).updateOwner(52, 21);
        verify(companyService, never()).updateOwner(52, 22);
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
    void otherTenantAndUnauthorizedActorsFailBeforeDomainExecution() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);
        when(workspaceService.getCurrentUserId()).thenReturn(77);
        when(chatMapper.getSessionByIdForUpdate(99, 77, TURN.sessionId())).thenReturn(null);

        assertThrows(
                ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), 29));

        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .lockAndRequirePermissionsSnapshot(eq(99), any());
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

    /**
     * The entry gates are deliberately asymmetric: executing and approving need workspace AI to be
     * enabled, while undoing an executed write and rejecting a proposal need only membership, so a
     * member can still take back or decline what the assistant did after an administrator turns it
     * off.
     */
    @Test
    void undoAndRejectionStillWorkAfterWorkspaceAiIsDisabled() throws Exception {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            created.setStatus("todo");
            return created;
        }).when(taskService).create(any(Task.class));
        AiAssistantPreparedWrite write = prepared(
                "create_task",
                "{\"handle\":\"r1\",\"description\":\"Send the renewal deck\"}",
                "person",
                31);
        stored(write, 29);
        service.executeAuto(TURN, 29, result -> { });
        storedToolCall.setStatus("executed");
        storedToolCall.setResultJson(capturedResultJson());
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(false);
        doAnswer(invocation -> null).when(taskService).deleteIf(eq(74), any());

        assertEquals("undone", service.undo(TURN.sessionId(), 29).status());
        verify(taskService).deleteIf(eq(74), any());

        AiAssistantPreparedWrite proposal = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal",
                44);
        stored(proposal, 29);
        storedToolCall.setStatus("proposed");
        storedToolCall.setResultJson(null);
        when(chatMapper.updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(29),
                eq("rejected"), any(), eq(TURN.userId()))).thenReturn(1);

        assertEquals("rejected", service.reject(TURN.sessionId(), 29).status());
        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));
        verify(governanceService, times(2)).isEnabled(TURN.workspaceId());
    }

    /**
     * A stored row whose name is not a registered write — a read tool's, one forged with a write
     * tier, or a name the catalog no longer declares — is refused as an invalid proposal by every
     * decision: approval before any authority lock, and rejection and undo before any status write
     * or domain call.
     */
    @Test
    void aStoredRowNamingNoRegisteredWriteToolIsRefusedByEveryDecision() {
        for (String[] row : List.of(
                new String[] {"list_tasks", "read"},
                new String[] {"list_tasks", "auto"},
                new String[] {"update_record_fields", "confirm"})) {
            storedToolCall.setId(29);
            storedToolCall.setToolName(row[0]);
            storedToolCall.setArgumentsJson("{\"tool\":\"" + row[0] + "\",\"tier\":\"" + row[1]
                    + "\",\"restrictionEpoch\":23,\"target\":{\"kind\":\"person\",\"id\":31},"
                    + "\"request\":{\"handle\":\"r1\"}}");
            storedToolCall.setStatus("proposed");
            storedToolCall.setResultJson(null);

            IllegalStateException approved = assertThrows(
                    IllegalStateException.class,
                    () -> service.approve(TURN.sessionId(), 29));
            assertEquals("Assistant tool proposal is invalid", approved.getMessage(), row[0]);
            verify(workspaceService, never()).lockAndRequirePermissionsSnapshot(anyInt(), any());

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> service.reject(TURN.sessionId(), 29));
            assertEquals("Assistant tool proposal is invalid", rejected.getMessage(), row[0]);

            storedToolCall.setStatus("executed");
            storedToolCall.setResultJson("{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"},"
                    + "\"undo\":{\"status\":\"available\",\"expiresAt\":\"2026-03-06T15:10:00Z\","
                    + "\"entityKind\":\"task\",\"entityId\":74,\"fingerprint\":\"0f1e2d\"}}");
            IllegalStateException undone = assertThrows(
                    IllegalStateException.class,
                    () -> service.undo(TURN.sessionId(), 29));
            assertEquals("Assistant tool proposal is invalid", undone.getMessage(), row[0]);
            clearInvocations(workspaceService);
        }
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
        verify(chatMapper, never()).updateExecutedToolResult(
                anyInt(), anyInt(), any(), anyInt());
        verify(taskService, never()).deleteIf(anyInt(), any());
        verify(taskService, never()).create(any(Task.class));
        verify(dealService, never()).changeStage(any(DealService.LockedStageChange.class));
        verify(personService, never()).getPersonById(anyInt());
    }

    /**
     * A confirm-tier proposal stores the stage and the members its card is reviewed against as two
     * siblings after its request, which a reader predating them never looks at; an immediate
     * proposal is executed in its own turn and stores neither.
     */
    @Test
    void aConfirmProposalPinsWhatItResolvedAndAnImmediateOneDoesNot() throws Exception {
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(21, "Grace Hopper"), member(11, "Ada Owner")));

        assertEquals(
                "{\"tool\":\"change_deal_stage\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"deal\",\"id\":44},"
                        + "\"request\":{\"handle\":\"r1\",\"stage\":\" proposal\"},"
                        + "\"resolution\":{\"field\":\"stage\",\"id\":6},\"principals\":[]}",
                prepared(
                        "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\" proposal\"}",
                        "deal", 44).argumentsJson());
        assertEquals(
                "{\"tool\":\"assign_owner\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"company\",\"id\":52},"
                        + "\"request\":{\"handle\":\"r1\",\"owner\":\"grace hopper\"},"
                        + "\"principals\":[21]}",
                prepared(
                        "assign_owner", "{\"handle\":\"r1\",\"owner\":\"grace hopper\"}",
                        "company", 52).argumentsJson());
        assertEquals(
                "{\"tool\":\"assign_owner\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"deal\",\"id\":44},"
                        + "\"request\":{\"handle\":\"r1\",\"owner\":\"Unassigned\"},"
                        + "\"principals\":[]}",
                prepared(
                        "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Unassigned\"}",
                        "deal", 44).argumentsJson());
        String immediate = prepared(
                "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}", "person", 31)
                .argumentsJson();
        assertFalse(immediate.contains("principals"), immediate);
        assertFalse(immediate.contains("resolution"), immediate);
    }

    /**
     * A name that resolves to no single row is refused before any proposal exists, recoverably and
     * with a reason that names no row, so the member is never shown a card its approval could only
     * refuse.
     */
    @Test
    void aNameThatResolvesToNoSingleRowIsRefusedRecoverablyBeforeAnythingIsStored() {
        User grace = member(21, "Grace Hopper");
        User admiral = member(22, "Admiral");
        admiral.setUsername("grace hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(grace, admiral));

        AiAssistantLoopException unknownStage = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Closed won\"}",
                        "deal", 44));
        AiAssistantLoopException ambiguousOwner = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                        "company", 52));
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(admiral));
        AiAssistantLoopException unknownOwner = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Ada Owner\"}",
                        "company", 52));

        for (AiAssistantLoopException refusal
                : List.of(unknownStage, ambiguousOwner, unknownOwner)) {
            assertTrue(refusal.recoverable());
            assertEquals("unresolved_reference", refusal.detailReason());
            assertEquals("malformed_output", refusal.terminalReason());
        }
        verify(dealService, never()).lockStageChangeRowsForUpdate(anyInt(), anyInt());
        verify(workspaceService, never()).lockAndRequirePermissionsSnapshot(anyInt(), any());
    }

    /**
     * A stage renamed away and another renamed into the reviewed name after the proposal: the name
     * now resolves to a stage the card never named, so the approval is refused before any board
     * row is locked, and nothing is written.
     */
    @Test
    void anApprovalWhoseStageNameNowResolvesToAnotherStageIsRefusedBeforeAnyLock()
            throws Exception {
        stored(prepared(
                "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}", "deal", 44),
                29);
        when(pipelineService.getAllStages()).thenReturn(List.of(
                pipelineStage(6, "Negotiation"), pipelineStage(7, "Proposal")));

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verify(dealService, never()).lockStageChangeRowsForUpdate(anyInt(), anyInt());
        verify(dealService, never()).changeStage(any(DealService.LockedStageChange.class));
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    /**
     * Issue 1865: the card reviewed member 21 as the owner; member 21 is offboarded and member 22
     * takes the same display name. The name now resolves uniquely to an active member and the
     * record is unchanged, so only the pin stands between the approval and a write to a member the
     * approver never saw.
     */
    @Test
    void anApprovalWhoseOwnerNameNowNamesAnotherMemberIsRefusedAndWritesNothing()
            throws Exception {
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(21, "Grace Hopper"), member(11, "Ada Owner")));
        stored(prepared(
                "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company", 52), 29);
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(22, "Grace Hopper"), member(11, "Ada Owner")));
        Company unchanged = new Company();
        unchanged.setId(52);
        unchanged.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(companyService.lockOwnedCompanyForUpdate(52)).thenReturn(unchanged);
        Company handedOver = new Company();
        handedOver.setId(52);
        handedOver.setOwnerId(22);
        when(companyService.updateOwner(52, 22)).thenReturn(handedOver);

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verify(companyService, never()).lockOwnedCompanyForUpdate(anyInt());
        verify(companyService, never()).updateOwner(anyInt(), any());
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    /**
     * The drifted member is compared with the pin before any authorization lock: member 22, who
     * took the reviewed name and whose account deletion is reserved, is never locked or asked
     * about, and the approver is told the proposal changed rather than that permission was lost.
     */
    @Test
    void aDriftedPrincipalIsRefusedBeforeItsAuthorizationRowsAreLocked() throws Exception {
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(21, "Grace Hopper"), member(11, "Ada Owner")));
        stored(prepared(
                "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company", 52), 29);
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(22, "Grace Hopper"), member(11, "Ada Owner")));
        doThrow(new ForbiddenException("User 22 is not a member of this workspace"))
                .when(workspaceService)
                .lockAndRequirePermissionsSnapshot(
                        TURN.workspaceId(),
                        Map.of(TURN.userId(), Set.of(Permission.AI_USE), 22, Set.of()));

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verify(workspaceService, never()).lockAndRequirePermissionsSnapshot(anyInt(), any());
        verify(chatMapper, never()).getToolCallBySessionForUpdate(anyInt(), anyInt(), anyInt());
        verify(companyService, never()).updateOwner(anyInt(), any());
    }

    /**
     * A tag removal pins the one tag its name resolved to, and its approval removes exactly that
     * tag through the record's own service, reporting whether anything was removed and recording
     * no undo and no divergence.
     */
    @Test
    void aTagRemovalPinsItsTagAndItsApprovalRemovesThatTagThroughTheRecordService()
            throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of(tag(8, "Prospect"), tag(9, "Priority")));
        AiAssistantPreparedWrite write = prepared(
                "remove_tag", "{\"handle\":\"r1\",\"tag\":\" priority\"}", "person", 31);
        assertEquals(
                "{\"tool\":\"remove_tag\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"person\",\"id\":31},"
                        + "\"request\":{\"handle\":\"r1\",\"tag\":\" priority\"},"
                        + "\"resolution\":{\"field\":\"tag\",\"id\":9},\"principals\":[]}",
                write.argumentsJson());
        stored(write, 29);
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        Person unchanged = person(31);
        unchanged.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(unchanged);
        when(personService.getPersonById(31)).thenReturn(unchanged);
        when(personService.removeTag(31, 9)).thenReturn(true);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(personService).removeTag(31, 9);
        verify(personService, never()).removeTagIfUnchanged(anyInt(), anyInt());
        JsonNode result = objectMapper.readTree(capturedResultJson());
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"person\",\"tag\":\"Priority\","
                        + "\"changed\":true}",
                result.get("outcome").toString());
        assertFalse(result.has("undo"), "a tag removal records no inverse");
        assertFalse(result.has("verification"), "a structural read-back never diverges");
    }

    /**
     * The reviewed tag is deleted and another is created under the same name after the proposal:
     * the name now resolves to a tag the card never named, so the approval is refused before the
     * record is locked, and no association is removed.
     */
    @Test
    void aTagRemovalWhoseTagWasRecreatedUnderTheSameNameIsRefusedBeforeAnyLock()
            throws Exception {
        when(tagService.getAllTags()).thenReturn(List.of(tag(9, "Priority")));
        stored(prepared(
                "remove_tag", "{\"handle\":\"r1\",\"tag\":\"Priority\"}", "person", 31), 29);
        when(tagService.getAllTags()).thenReturn(List.of(tag(10, "Priority")));

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt());
        verify(personService, never()).removeTag(anyInt(), anyInt());
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    /** A tag name that matches no workspace tag is refused recoverably before anything is stored. */
    @Test
    void aTagRemovalNamingNoWorkspaceTagIsRefusedRecoverably() {
        when(tagService.getAllTags()).thenReturn(List.of(tag(9, "Priority")));

        AiAssistantLoopException refusal = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "remove_tag", "{\"handle\":\"r1\",\"tag\":\"Dormant\"}",
                        "company", 52));

        assertTrue(refusal.recoverable());
        assertEquals("unresolved_reference", refusal.detailReason());
        verify(companyService, never()).removeTag(anyInt(), anyInt());
    }

    /**
     * The tag row is never locked, so a rename can commit after the approval resolved the reviewed
     * tag and before the record lock is held. The same association is removed, because the pin
     * compares ids, and the stored outcome names the tag as it reads after the record lock, which
     * is the name the record service's audit row records, not the one it had lost by then.
     */
    @Test
    void aTagRenamedWhileItsRemovalIsApprovedIsNamedInTheOutcomeAsItReadsAfterTheRecordLock()
            throws Exception {
        AtomicBoolean recordLocked = new AtomicBoolean();
        when(tagService.getAllTags()).thenAnswer(invocation -> recordLocked.get()
                ? List.of(tag(9, "Urgent"))
                : List.of(tag(9, "Priority")));
        stored(prepared(
                "remove_tag", "{\"handle\":\"r1\",\"tag\":\"Priority\"}", "person", 31), 29);
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        Person unchanged = person(31);
        unchanged.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(personService.lockProcessablePersonForUpdate(31)).thenAnswer(invocation -> {
            recordLocked.set(true);
            return unchanged;
        });
        when(personService.getPersonById(31)).thenReturn(unchanged);
        when(personService.removeTag(31, 9)).thenReturn(true);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(personService).removeTag(31, 9);
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"person\",\"tag\":\"Urgent\","
                        + "\"changed\":true}",
                objectMapper.readTree(capturedResultJson()).get("outcome").toString());
    }

    /**
     * Tag names are stored as members typed them, so a name with surrounding whitespace is a
     * valid tag the model can name exactly; it resolves and is pinned, and an exact match beats a
     * tag that equals the request only once the whitespace is stripped.
     */
    @Test
    void aTagRemovalResolvesATagStoredWithSurroundingWhitespaceByItsExactName() throws Exception {
        when(tagService.getAllTags()).thenReturn(
                List.of(tag(8, "Priority"), tag(9, " Priority ")));

        AiAssistantPreparedWrite spaced = prepared(
                "remove_tag", "{\"handle\":\"r1\",\"tag\":\" priority \"}", "person", 31);
        AiAssistantPreparedWrite bare = prepared(
                "remove_tag", "{\"handle\":\"r1\",\"tag\":\"Priority\"}", "person", 31);

        assertEquals(9, objectMapper.readTree(spaced.argumentsJson())
                .path("resolution").path("id").asInt());
        assertEquals(8, objectMapper.readTree(bare.argumentsJson())
                .path("resolution").path("id").asInt());
    }

    /**
     * A name that equals no tag as stored and more than one once the whitespace around both sides
     * is stripped is ambiguous, and is refused recoverably rather than guessed.
     */
    @Test
    void aTagRemovalMatchingTwoTagsOnlyOnceWhitespaceIsStrippedIsRefusedAsAmbiguous() {
        when(tagService.getAllTags()).thenReturn(
                List.of(tag(8, "Priority"), tag(9, " Priority ")));

        AiAssistantLoopException refusal = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "remove_tag", "{\"handle\":\"r1\",\"tag\":\"priority \"}",
                        "person", 31));

        assertTrue(refusal.recoverable());
        assertEquals("unresolved_reference", refusal.detailReason());
        verify(personService, never()).removeTag(anyInt(), anyInt());
    }

    /**
     * A first-response deadline is proposed with no pin to compare, and its approval starts the
     * clock through the service the workflow engine's action calls, with the reviewed hours,
     * reporting whether a clock started and recording no undo and no divergence.
     */
    @Test
    void aResponseDeadlineApprovalStartsTheClockThroughTheLeadResponseService()
            throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "set_response_due", "{\"handle\":\"r1\",\"due_in_hours\":48}", "person", 31);
        assertEquals(
                "{\"tool\":\"set_response_due\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"person\",\"id\":31},"
                        + "\"request\":{\"handle\":\"r1\",\"due_in_hours\":48},"
                        + "\"principals\":[]}",
                write.argumentsJson());
        stored(write, 29);
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        Person unchanged = person(31);
        unchanged.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(unchanged);
        when(personService.getPersonById(31)).thenReturn(unchanged);
        when(leadResponseSlaService.startFirstResponseClock(31, 48)).thenReturn(true);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(leadResponseSlaService).startFirstResponseClock(31, 48);
        JsonNode result = objectMapper.readTree(capturedResultJson());
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"person\",\"dueInHours\":48,"
                        + "\"changed\":true}",
                result.get("outcome").toString());
        assertFalse(result.has("undo"), "a response deadline records no inverse");
        assertFalse(result.has("verification"), "a structural read-back never diverges");
    }

    /**
     * The contact was written after the deadline was proposed, so the approval is refused on the
     * framework's own freshness rule after the contact is locked and before any clock starts.
     */
    @Test
    void aResponseDeadlineOnAContactWrittenAfterTheProposalIsRefusedAndStartsNoClock()
            throws Exception {
        stored(prepared(
                "set_response_due", "{\"handle\":\"r1\",\"due_in_hours\":24}", "person", 31),
                29);
        storedToolCall.setCreatedAt("2026-03-06 14:59:00.000000");
        Person edited = person(31);
        edited.setUpdatedAt("2026-03-06 14:59:30.000000");
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(edited);
        when(personService.getPersonById(31)).thenReturn(edited);

        ConflictException refused = assertThrows(
                ConflictException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verify(personService).lockProcessablePersonForUpdate(31);
        verify(leadResponseSlaService, never()).startFirstResponseClock(anyInt(), any());
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    /**
     * The contact update permission is revoked after the proposal, so the framework refuses the
     * approval from the locked snapshot before the contact is locked or the service is reached.
     */
    @Test
    void aResponseDeadlineWhoseApproverLostContactUpdateIsRefusedBeforeAnyLock()
            throws Exception {
        stored(prepared(
                "set_response_due", "{\"handle\":\"r1\",\"due_in_hours\":24}", "person", 31),
                29);
        grantAllExcept(Permission.PERSON_UPDATE);

        ForbiddenException refused = assertThrows(
                ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        assertEquals(
                "Requires the PERSON_UPDATE permission in this workspace", refused.getMessage());
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt());
        verify(leadResponseSlaService, never()).startFirstResponseClock(anyInt(), any());
    }

    /**
     * A contact shared in from another workspace is visible here but never writable through the
     * clock service, so its deadline is refused recoverably when proposed: nothing is stored, no
     * card can offer an approval that could only fail, and the reason names no row.
     */
    @Test
    void aResponseDeadlineOnAContactSharedInFromAnotherWorkspaceIsRefusedRecoverably() {
        when(personService.isOwnedByCurrentWorkspace(31)).thenReturn(false);

        AiAssistantLoopException refusal = assertThrows(
                AiAssistantLoopException.class,
                () -> prepared(
                        "set_response_due", "{\"handle\":\"r1\",\"due_in_hours\":24}",
                        "person", 31));

        assertTrue(refusal.recoverable());
        assertEquals("unresolved_reference", refusal.detailReason());
        verify(personService).isOwnedByCurrentWorkspace(31);
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt());
        verify(leadResponseSlaService, never()).startFirstResponseClock(anyInt(), any());
    }

    /**
     * Ownership is asked only of a tool whose delegate writes an owned record: a tag on the same
     * shared-in contact is proposed as before, because its service accepts a visible contact.
     */
    @Test
    void onlyAToolThatRequiresAnOwnedTargetAsksWhetherTheContactIsOwned() throws Exception {
        when(personService.isOwnedByCurrentWorkspace(31)).thenReturn(false);
        when(tagService.getAllTags()).thenReturn(List.of(tag(9, "Priority")));

        prepared("remove_tag", "{\"handle\":\"r1\",\"tag\":\"Priority\"}", "person", 31);

        verify(personService, never()).isOwnedByCurrentWorkspace(anyInt());
    }

    /**
     * A deadline outside the service's own one-hour-to-one-year range, or one that is not a whole
     * number, is refused before anything is stored.
     */
    @Test
    void aResponseDeadlineOutsideTheServicesRangeIsRefusedBeforeAnythingIsStored() {
        for (String hours : List.of("0", "8761", "\"[redacted]\"")) {
            AiAssistantLoopException refusal = assertThrows(
                    AiAssistantLoopException.class,
                    () -> prepared(
                            "set_response_due",
                            "{\"handle\":\"r1\",\"due_in_hours\":" + hours + "}",
                            "person", 31),
                    hours);
            assertEquals("invalid_tool_arguments", refusal.detailReason(), hours);
        }
        verify(leadResponseSlaService, never()).startFirstResponseClock(anyInt(), any());
    }

    /**
     * A proposal stored before pinning carries neither pin and is approved exactly as it always
     * was: its names are resolved again before the lock and whatever they resolve to is written.
     */
    @Test
    void aProposalStoredBeforePinningIsApprovedByNameExactlyAsBefore() throws Exception {
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(22, "Grace Hopper")));
        storedToolCall.setId(29);
        storedToolCall.setToolName("assign_owner");
        storedToolCall.setArgumentsJson(
                "{\"tool\":\"assign_owner\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"company\",\"id\":52},"
                        + "\"request\":{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}}");
        Company owned = new Company();
        owned.setId(52);
        owned.setOwnerId(22);
        when(companyService.updateOwner(52, 22)).thenReturn(owned);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(companyService).updateOwner(52, 22);
        verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(),
                Map.of(TURN.userId(), Set.of(Permission.AI_USE), 22, Set.of()));
    }

    /** A stage proposal stored before pinning moves the deal to whatever its name resolves to. */
    @Test
    void aStageProposalStoredBeforePinningIsApprovedByNameExactlyAsBefore() throws Exception {
        when(pipelineService.getAllStages()).thenReturn(List.of(
                pipelineStage(6, "Negotiation"), pipelineStage(7, "Proposal")));
        storedToolCall.setId(29);
        storedToolCall.setToolName("change_deal_stage");
        storedToolCall.setArgumentsJson(
                "{\"tool\":\"change_deal_stage\",\"tier\":\"confirm\",\"restrictionEpoch\":23,"
                        + "\"target\":{\"kind\":\"deal\",\"id\":44},"
                        + "\"request\":{\"handle\":\"r1\",\"stage\":\"Proposal\"}}");
        DealService.LockedStageChange locked = mock(DealService.LockedStageChange.class);
        when(dealService.lockStageChangeRowsForUpdate(44, 7)).thenReturn(locked);
        Deal moved = new Deal();
        moved.setId(44);
        moved.setStageId(7);
        when(dealService.changeStage(locked)).thenReturn(moved);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(dealService).changeStage(locked);
    }

    /**
     * Only the framework writes pins, so a stored proposal whose pins do not parse is metadata it
     * never wrote: an approval refuses it before any authority lock, and a rejection before it
     * writes a status.
     */
    @Test
    void aProposalWithMalformedPinsIsRefusedByEveryDecision() {
        String legacy = "{\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                + "\"restrictionEpoch\":23,\"target\":{\"kind\":\"deal\",\"id\":44},"
                + "\"request\":{\"handle\":\"r1\",\"stage\":\"Proposal\"}";
        storedToolCall.setId(29);
        storedToolCall.setToolName("change_deal_stage");
        for (String pins : List.of(
                ",\"resolution\":{\"field\":\"stage\",\"id\":6}}",
                ",\"principals\":{}}",
                ",\"principals\":[\"21\"]}",
                ",\"principals\":[22,21]}",
                ",\"principals\":[21,21]}",
                ",\"principals\":[0]}",
                ",\"resolution\":{\"field\":\"stage\"},\"principals\":[]}",
                ",\"resolution\":{\"field\":\"\",\"id\":6},\"principals\":[]}",
                ",\"resolution\":{\"field\":\"stage\",\"id\":6,\"label\":\"x\"},"
                        + "\"principals\":[]}")) {
            storedToolCall.setArgumentsJson(legacy + pins);

            assertThrows(
                    IllegalStateException.class, () -> service.approve(TURN.sessionId(), 29),
                    pins);
            assertThrows(
                    IllegalStateException.class, () -> service.reject(TURN.sessionId(), 29),
                    pins);
        }
        verify(dealService, never()).lockStageChangeRowsForUpdate(anyInt(), anyInt());
        verify(dealService, never()).changeStage(any(DealService.LockedStageChange.class));
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    /**
     * A step reached again after its proposal was stored replays the stored pins verbatim. The
     * reviewed member was offboarded and the stage renamed away since, so the same calls prepared
     * afresh are now unresolved; replayed, neither name is resolved again and each rebuilt proposal
     * is byte-identical to the stored one. A stored row whose pins do not parse is refused like
     * any other stored proposal that no longer parses.
     */
    @Test
    void aReplayCarriesTheStoredPinsAndResolvesNothingAgain() throws Exception {
        String ownerCall = "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}";
        String stageCall = "{\"handle\":\"r1\",\"stage\":\"Proposal\"}";
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(21, "Grace Hopper")));
        String storedOwner = prepared("assign_owner", ownerCall, "company", 52).argumentsJson();
        String storedStage = prepared("change_deal_stage", stageCall, "deal", 44).argumentsJson();
        when(workspaceService.getMembers(TURN.workspaceId()))
                .thenReturn(List.of(member(11, "Ada Owner")));
        when(pipelineService.getAllStages())
                .thenReturn(List.of(pipelineStage(6, "Proposal (retired)")));
        for (AiAssistantLoopException refusal : List.of(
                assertThrows(AiAssistantLoopException.class,
                        () -> prepared("assign_owner", ownerCall, "company", 52)),
                assertThrows(AiAssistantLoopException.class,
                        () -> prepared("change_deal_stage", stageCall, "deal", 44)))) {
            assertEquals("unresolved_reference", refusal.detailReason());
        }
        clearInvocations(workspaceService, pipelineService, dealService);

        assertEquals(storedOwner,
                replayed("assign_owner", ownerCall, "company", 52, storedOwner).argumentsJson());
        assertEquals(storedStage,
                replayed("change_deal_stage", stageCall, "deal", 44, storedStage)
                        .argumentsJson());
        verify(workspaceService, never()).getMembers(anyInt());
        verify(pipelineService, never()).getAllStages();
        verify(dealService, never()).getDealById(anyInt());

        String malformed = storedStage.replace("\"principals\":[]", "\"principals\":[0]");
        assertThrows(IllegalStateException.class,
                () -> replayed("change_deal_stage", stageCall, "deal", 44, malformed));
    }

    private void grantAllExcept(Permission... revoked) {
        EnumSet<Permission> granted = EnumSet.allOf(Permission.class);
        granted.removeAll(List.of(revoked));
        when(authority.effectiveFor(TURN.userId())).thenReturn(granted);
    }

    private AiAssistantPreparedWrite prepared(
            String tool, String json, String targetKind, int targetId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        return service.prepare(
                tool, objectMapper.readTree(json), resources, TURN.restrictionEpoch());
    }

    private AiAssistantPreparedWrite replayed(
            String tool, String json, String targetKind, int targetId, String storedArguments)
            throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        return service.prepareReplay(
                tool, objectMapper.readTree(json), resources, TURN.restrictionEpoch(),
                storedArguments);
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

    private static Stage pipelineStage(int id, String name) {
        ooo.klae.connex.backend.beans.Pipeline pipeline =
                new ooo.klae.connex.backend.beans.Pipeline();
        pipeline.setId(5);
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        stage.setPipeline(pipeline);
        return stage;
    }

    private static Tag tag(int id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    private static User member(int id, String displayName) {
        User member = new User();
        member.setId(id);
        member.setDisplayName(displayName);
        return member;
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
