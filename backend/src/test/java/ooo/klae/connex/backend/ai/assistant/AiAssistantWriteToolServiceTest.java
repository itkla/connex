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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.ActivityService;
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
            7, 11, 13, 17, 19, 1, 23L, true, List.of());

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
        AiRestrictionEpoch restrictionEpoch = mock(AiRestrictionEpoch.class);
        AuthService authService = mock(AuthService.class);
        User actor = new User();
        actor.setId(TURN.userId());
        actor.setTimezone("America/New_York");
        when(authService.getCurrentUser()).thenReturn(actor);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(workspaceService.getCurrentUserId()).thenReturn(TURN.userId());
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(true);
        AiAssistantDateResolver dateResolver = new AiAssistantDateResolver(authService, CLOCK);
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        AiAssistantToolExecutor readExecutor = new AiAssistantToolExecutor(
                catalog,
                mock(SearchService.class),
                personService,
                companyService,
                dealService,
                activityService,
                taskService,
                mock(ScoringService.class),
                workspaceService,
                mock(PersonMapper.class),
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
        Activity conflict = activity(88, "Existing meeting", "2026-03-12 13:30:00");
        when(activityService.getActivitiesByPersonId(31)).thenReturn(List.of(conflict));
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\","
                        + "\"subject\":\"Planning\",\"start\":\"9:00am next Thursday\","
                        + "\"duration_minutes\":60,\"idempotency_key\":\"meeting-replay-1\"}",
                "person",
                31);
        stored(write, 29);

        AiAssistantWriteToolService.WriteExecution execution = service.executeAuto(TURN, 29);

        assertEquals("2026-03-12 13:00:00", execution.toolResult().data().get("start"));
        assertEquals("America/New_York", execution.toolResult().data().get("timezone"));
        assertEquals(1, ((List<?>) execution.toolResult().data().get("conflicts")).size());
        assertTrue(execution.toolCall().undoAvailable());
        verify(activityService).getActivitiesByPersonId(31);
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
        when(activityService.getActivitiesByPersonId(31)).thenReturn(List.of());
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantPreparedWrite write = prepared(
                "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\","
                        + "\"subject\":\"Planning\",\"start\":\"9:00am next Thursday\","
                        + "\"idempotency_key\":\"meeting-replay-2\"}",
                "person",
                31);
        stored(write, 29);
        service.executeAuto(TURN, 29);
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
                        + "\"due_date\":\"2026-03-12\","
                        + "\"idempotency_key\":\"task-replay-1\"}",
                "deal",
                44);
        stored(write, 29);

        assertEquals("task", service.executeAuto(TURN, 29)
                .toolResult().data().get("recordType"));
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
                        + "\"visibility\":\"workspace\","
                        + "\"idempotency_key\":\"note-replay-1\"}",
                "person",
                31);
        stored(write, 29);

        assertEquals("note", service.executeAuto(TURN, 29)
                .toolResult().data().get("recordType"));
        verify(noteService).create(any(Note.class));
    }

    @Test
    void addTagDelegatesToTheRecordNativeServiceAndOffersOnlyARealInverse() throws Exception {
        Tag tag = new Tag();
        tag.setId(9);
        tag.setName("Priority");
        when(tagService.getAllTags()).thenReturn(List.of(tag));
        when(personService.getTagsByPersonId(31)).thenReturn(List.of());
        AiAssistantPreparedWrite write = prepared(
                "add_tag",
                "{\"handle\":\"r1\",\"tag\":\"Priority\","
                        + "\"idempotency_key\":\"tag-replay-1\"}",
                "person",
                31);
        stored(write, 29);

        AiAssistantWriteToolService.WriteExecution execution = service.executeAuto(TURN, 29);

        assertTrue(execution.toolCall().undoAvailable());
        verify(personService).addTag(31, 9);
    }

    @Test
    void confirmTierNeverExecutesBeforeApprovalAndDoubleApprovalIsIdempotent() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\","
                        + "\"idempotency_key\":\"stage-replay-1\"}",
                "deal",
                44);
        stored(write, 29);
        AiAssistantToolProposal proposal = new AiAssistantToolProposal(29, "proposed", null, true);

        assertEquals(
                "approval_required",
                service.proposalResult(write, proposal).data().get("status"));
        verify(dealService, never()).changeStage(44, 6);

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
        when(dealService.getDealById(44)).thenReturn(deal);
        when(pipelineService.getAllStages()).thenReturn(List.of(stage));
        when(dealService.changeStage(44, 6)).thenReturn(deal);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());
        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());
        verify(dealService, times(1)).changeStage(44, 6);
    }

    @Test
    void permissionRevokedAfterProposalBlocksApprovalExecution() throws Exception {
        AiAssistantPreparedWrite write = prepared(
                "change_deal_stage",
                "{\"handle\":\"r1\",\"stage\":\"Proposal\","
                        + "\"idempotency_key\":\"stage-replay-2\"}",
                "deal",
                44);
        stored(write, 29);
        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .requirePermission(TURN.workspaceId(), TURN.userId(), Permission.DEAL_UPDATE);

        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), 29));

        verify(dealService, never()).changeStage(44, 6);
    }

    @Test
    void ownerAssignmentExecutesOnlyThroughTheNativeRecordServiceAfterApproval() throws Exception {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        AiAssistantPreparedWrite write = prepared(
                "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\","
                        + "\"idempotency_key\":\"owner-replay-1\"}",
                "company",
                52);
        stored(write, 29);

        assertEquals("executed", service.approve(TURN.sessionId(), 29).status());

        verify(companyService).updateOwner(52, 21);
        verify(personService, never()).updateOwner(52, 21);
        verify(dealService, never()).updateOwner(52, 21);
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
        assertThrows(ForbiddenException.class, () -> service.reject(TURN.sessionId(), 29));
        verify(dealService, never()).changeStage(44, 6);
    }

    private AiAssistantPreparedWrite prepared(
            String tool, String json, String targetKind, int targetId) throws Exception {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register(targetKind, targetId);
        return service.prepare(tool, objectMapper.readTree(json), resources);
    }

    private void stored(AiAssistantPreparedWrite write, int id) {
        storedToolCall.setId(id);
        storedToolCall.setToolName(write.toolName());
        storedToolCall.setArgumentsJson(write.argumentsJson());
        storedToolCall.setIdempotencyKey(write.idempotencyKey());
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
