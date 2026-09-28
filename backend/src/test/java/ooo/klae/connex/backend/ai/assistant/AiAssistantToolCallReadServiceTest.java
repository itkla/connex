package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Review;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.SharedRequestFlag;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordTag;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantToolCallReadServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final int SESSION_ID = 13;
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC);

    private AiChatMapper chatMapper;
    private WorkspaceService workspaceService;
    private PersonMapper personMapper;
    private DealMapper dealMapper;
    private PipelineMapper pipelineMapper;
    private TagMapper tagMapper;
    private ActivityMapper activityMapper;
    private TaskMapper taskMapper;
    private NoteMapper noteMapper;
    private AiAssistantSessionReadAudit sessionReadAudit;
    private AiChatSession accessibleSession;
    private AiAssistantToolCallReadService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        workspaceService = mock(WorkspaceService.class);
        personMapper = mock(PersonMapper.class);
        dealMapper = mock(DealMapper.class);
        pipelineMapper = mock(PipelineMapper.class);
        tagMapper = mock(TagMapper.class);
        activityMapper = mock(ActivityMapper.class);
        taskMapper = mock(TaskMapper.class);
        noteMapper = mock(NoteMapper.class);
        sessionReadAudit = mock(AiAssistantSessionReadAudit.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of(
                Permission.ACTIVITY_CREATE,
                Permission.ACTIVITY_DELETE,
                Permission.TASK_CREATE,
                Permission.TASK_DELETE,
                Permission.NOTE_CREATE,
                Permission.NOTE_DELETE,
                Permission.PERSON_UPDATE,
                Permission.COMPANY_UPDATE,
                Permission.DEAL_UPDATE));
        when(workspaceService.getMembers(WORKSPACE_ID))
                .thenReturn(List.of(user(USER_ID, "Ada Owner", "ada-owner")));
        accessibleSession = new AiChatSession();
        accessibleSession.setId(SESSION_ID);
        accessibleSession.setCreatedByUserId(USER_ID);
        accessibleSession.setStatus("active");
        when(chatMapper.getAccessibleSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID)).thenReturn(accessibleSession);
        service = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                stageTool(),
                tagTool(),
                removeTagTool(),
                ownerTool()));
    }

    private AiAssistantToolCallReadService service(List<AiAssistantWriteTool> tools) {
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        return new AiAssistantToolCallReadService(
                catalog,
                new AiAssistantWriteToolRegistry(catalog, tools),
                chatMapper,
                workspaceService,
                personMapper,
                mock(CompanyMapper.class),
                dealMapper,
                pipelineMapper,
                tagMapper,
                activityMapper,
                taskMapper,
                noteMapper,
                sessionReadAudit,
                JsonMapper.builder().build(),
                CLOCK);
    }

    private static AiAssistantCreateActivityWriteTool activityTool() {
        return new AiAssistantCreateActivityWriteTool(
                mock(ActivityService.class),
                mock(AiAssistantDateResolver.class),
                JsonMapper.builder().build());
    }

    private static AiAssistantCreateNoteWriteTool noteTool() {
        return new AiAssistantCreateNoteWriteTool(
                mock(NoteService.class), JsonMapper.builder().build());
    }

    private static AiAssistantAddTagWriteTool tagTool() {
        return new AiAssistantAddTagWriteTool(
                mock(TagService.class),
                mock(PersonService.class),
                mock(CompanyService.class),
                mock(DealService.class));
    }

    private static AiAssistantRemoveTagWriteTool removeTagTool() {
        return new AiAssistantRemoveTagWriteTool(
                mock(TagService.class),
                mock(PersonService.class),
                mock(CompanyService.class),
                mock(DealService.class));
    }

    private static AiAssistantAssignOwnerWriteTool ownerTool() {
        return new AiAssistantAssignOwnerWriteTool(
                mock(PersonService.class), mock(CompanyService.class), mock(DealService.class));
    }

    private static AiAssistantChangeDealStageWriteTool stageTool() {
        return new AiAssistantChangeDealStageWriteTool(
                mock(DealService.class), mock(PipelineService.class));
    }

    @Test
    void terminalAutoCallIncludesAssistantAssociationAndAbsoluteUndoExpiry() {
        AiChatToolCall toolCall = toolCall(
                29,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"content\":\"private note text\"},\"undo\":{"
                        + "\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\","
                        + "\"fingerprint\":\"private fingerprint\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(19), 100))
                .thenReturn(List.of(assistantMessage(91, 19)));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));
        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("executed", result.status());
        assertEquals("Create a note", result.requestSummary());
        assertEquals("Note created", result.outcomeSummary());
        assertEquals(91, result.messageId());
        assertEquals(19, result.turnId());
        assertEquals("2026-08-12T12:10:00Z", result.undoExpiresAt());
        assertTrue(result.undoAvailable());
        assertEquals(31, result.target().id());
        assertEquals("Ada Lovelace", result.target().label());
        assertFalse(result.requestSummary().contains("private"));
        assertFalse(result.outcomeSummary().contains("private"));
    }

    /**
     * Both key shapes resolve a row to its turn, and a malformed key still drops it.
     *
     * <p>An executed or proposed write is always the only call of its step, so no such row
     * carries a call ordinal; parsing one anyway keeps the widening from being a landmine, and the
     * anchored pattern still refuses anything else outright. The ordinal is bounded by the
     * per-step call ceiling for the same reason the step number is bounded by the loop's backstop:
     * a position no step could have produced names no call this service should attribute.
     */
    @Test
    void aKeyNamingACallOrdinalResolvesItsTurnWhileAnImpossibleOneIsStillDropped() {
        AiChatToolCall suffixed = toolCall(
                29, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        suffixed.setIdempotencyKey("turn-19-step-2-call-3");
        AiChatToolCall malformedKey = toolCall(
                31, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        malformedKey.setIdempotencyKey("turn-19-step-2-call-");
        AiChatToolCall impossibleOrdinal = toolCall(
                33, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        impossibleOrdinal.setIdempotencyKey("turn-19-step-2-call-"
                + (AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1));
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(suffixed, malformedKey, impossibleOrdinal));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(19), 100))
                .thenReturn(List.of(assistantMessage(91, 19)));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(1, result.size());
        assertEquals(29, result.getFirst().id());
        assertEquals(19, result.getFirst().turnId());
        assertEquals(91, result.getFirst().messageId());
    }

    /**
     * Every key the grammar refuses drops its row, however valid the row is otherwise.
     *
     * <p>The card list ties a row to its turn only through the one key parser, so a key past the
     * step ceiling, zero-padded, carrying a zero ordinal, a segment the grammar does not have, or a
     * number too large to read names no turn, and its row is dropped beside a card that is read.
     */
    @Test
    void everyKeyTheGrammarRefusesDropsItsRow() {
        List<String> refusedKeys = List.of(
                "turn-19-step-" + (AiChatAgentLoopService.HARD_MAX_STEPS + 1),
                "turn-019-step-2",
                "turn-19-step-2-call-0",
                "turn-19-step-2-row-1",
                "turn-2147483648-step-2",
                "turn-19-step-2 ");
        List<AiChatToolCall> rows = new ArrayList<>();
        rows.add(toolCall(
                29, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}"));
        for (int index = 0; index < refusedKeys.size(); index++) {
            AiChatToolCall refused = toolCall(
                    40 + index, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                    "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
            refused.setIdempotencyKey(refusedKeys.get(index));
            rows.add(refused);
        }
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(rows);
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(19), 100))
                .thenReturn(List.of(assistantMessage(91, 19)));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(List.of(29), result.stream().map(AiAssistantToolCallReadDto::id).toList());
    }

    /**
     * A write refused whole with its batch leaves a suffixed failed row no card is built from.
     *
     * <p>{@code mixed_tier_step} writes one failed row per call of the refused batch, the write
     * among them, under that call's {@code -call-k} key. The write never reached
     * {@code writeToolService.prepare}, so its row holds the model's raw arguments rather than a
     * prepared write's tool, tier and target, and must be dropped however well its key parses —
     * while an ordinary card beside it is still read.
     */
    @Test
    void aWriteRefusedWholeWithItsBatchLeavesNoCardDespiteItsSuffixedKey() {
        AiChatToolCall card = toolCall(
                29, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        AiChatToolCall refusedWrite = toolCall(
                35, USER_ID, "create_task", "auto", "failed", "person", 31, 19,
                "{\"reason\":\"mixed_tier_step\"}");
        refusedWrite.setArgumentsJson("{\"handle\":\"r1\",\"title\":\"Call back\"}");
        refusedWrite.setIdempotencyKey("turn-19-step-2-call-2");
        refusedWrite.setExecutedAt(null);
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(card, refusedWrite));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(19), 100))
                .thenReturn(List.of(assistantMessage(91, 19)));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(List.of(29), result.stream().map(AiAssistantToolCallReadDto::id).toList());
    }

    /**
     * A call the loop refused keeps the model's raw arguments under the write tool's name as a
     * failed row, and those arguments can be shaped like a proposal. A confirm-tier row that does
     * not name, as text, the owner or stage it would propose is never a card, and a direct read of
     * it answers 404, however well its tool, tier and target match the declaration.
     */
    @Test
    void aRefusedCallShapedLikeAProposalWithoutItsProposedValueLeavesNoCard() {
        AiChatToolCall card = toolCall(
                29, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        AiChatToolCall ownerless = toolCall(
                36, USER_ID, "assign_owner", "confirm", "failed", "deal", 41, 20,
                "{\"reason\":\"invalid_tool_arguments\"}");
        ownerless.setArgumentsJson("{\"tool\":\"assign_owner\",\"tier\":\"confirm\","
                + "\"target\":{\"kind\":\"deal\",\"id\":41},\"request\":{\"handle\":\"r1\"}}");
        AiChatToolCall stageless = toolCall(
                37, USER_ID, "change_deal_stage", "confirm", "failed", "deal", 41, 21,
                "{\"reason\":\"invalid_tool_arguments\"}");
        stageless.setArgumentsJson("{\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                + "\"target\":{\"kind\":\"deal\",\"id\":41},\"request\":{\"handle\":\"r1\"}}");
        AiChatToolCall numericStage = toolCall(
                38, USER_ID, "change_deal_stage", "confirm", "failed", "deal", 41, 22,
                "{\"reason\":\"invalid_tool_arguments\"}");
        numericStage.setArgumentsJson("{\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                + "\"target\":{\"kind\":\"deal\",\"id\":41},"
                + "\"request\":{\"handle\":\"r1\",\"stage\":7}}");
        List<AiChatToolCall> refused = List.of(ownerless, stageless, numericStage);
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(card, ownerless, stageless, numericStage));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));
        stubVisibleDeal();

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(List.of(29), result.stream().map(AiAssistantToolCallReadDto::id).toList());
        for (AiChatToolCall row : refused) {
            when(chatMapper.getToolCallBySession(WORKSPACE_ID, SESSION_ID, row.getId()))
                    .thenReturn(row);
            assertThrows(
                    ResourceNotFoundException.class,
                    () -> service.get(SESSION_ID, row.getId()),
                    row.getToolName() + " " + row.getId());
        }
    }

    /**
     * A row whose stored tool matches its name but names no registered write — a read tool's, one
     * forged with a write tier, or a name the catalog no longer declares — is never a card, and a
     * direct read of it answers 404.
     */
    @Test
    void aRowNamingNoRegisteredWriteToolLeavesNoCard() {
        AiChatToolCall card = toolCall(
                29, USER_ID, "create_note", "auto", "executed", "person", 31, 19,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}");
        List<AiChatToolCall> unregistered = List.of(
                toolCall(40, USER_ID, "list_tasks", "read", "executed", "person", 31, 20,
                        "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}"),
                toolCall(41, USER_ID, "list_tasks", "auto", "executed", "person", 31, 21,
                        "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"}}"),
                toolCall(42, USER_ID, "update_record_fields", "confirm", "proposed", "person", 31,
                        22, null));
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(
                        card, unregistered.get(0), unregistered.get(1), unregistered.get(2)));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(List.of(29), result.stream().map(AiAssistantToolCallReadDto::id).toList());
        for (AiChatToolCall row : unregistered) {
            when(chatMapper.getToolCallBySession(WORKSPACE_ID, SESSION_ID, row.getId()))
                    .thenReturn(row);
            assertThrows(
                    ResourceNotFoundException.class,
                    () -> service.get(SESSION_ID, row.getId()),
                    row.getToolName() + " " + row.getId());
        }
    }

    @Test
    void sharedViewerGetsKindOnlyTargetAndCannotUndoAnotherParticipantsCall() {
        AiChatToolCall toolCall = toolCall(
                30,
                99,
                "create_task",
                "auto",
                "executed",
                "person",
                31,
                20,
                "{\"tier\":\"auto\",\"outcome\":{"
                        + "\"description\":\"participant secret\"},\"undo\":{"
                        + "\"status\":\"available\",\"entityKind\":\"task\","
                        + "\"entityId\":74,"
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(20), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31))).thenReturn(List.of());

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("person", result.target().kind());
        assertNull(result.target().id());
        assertNull(result.target().label());
        assertEquals("Create a task", result.requestSummary());
        assertEquals("Task created", result.outcomeSummary());
        assertFalse(result.undoAvailable());
        assertNull(result.messageId());
        assertNull(result.createdRecord());
        assertFalse(result.requestSummary().contains("secret"));
        assertFalse(result.outcomeSummary().contains("secret"));
    }

    @Test
    void pendingFilterAndSingleReadUseTheSameSafeProjection() {
        AiChatToolCall toolCall = toolCall(
                31,
                USER_ID,
                "assign_owner",
                "confirm",
                "proposed",
                "person",
                31,
                21,
                null);
        AiChatToolCall transientAutoCall = toolCall(
                34,
                USER_ID,
                "create_note",
                "auto",
                "proposed",
                "person",
                31,
                21,
                null);
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, true, 100))
                .thenReturn(List.of(toolCall, transientAutoCall));
        when(chatMapper.getToolCallBySession(
                WORKSPACE_ID, SESSION_ID, 31)).thenReturn(toolCall);
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(21), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> pending = service.list(SESSION_ID, true);
        AiAssistantToolCallReadDto listed = pending.getFirst();
        AiAssistantToolCallReadDto detail = service.get(SESSION_ID, 31);

        assertEquals(1, pending.size());
        assertEquals(31, listed.id());
        assertEquals("proposed", listed.status());
        assertEquals("Assign owner: Ada Owner", listed.requestSummary());
        assertNull(listed.messageId());
        assertNull(listed.outcomeSummary());
        assertEquals(listed, detail);
        verify(chatMapper).listToolCallsBySession(WORKSPACE_ID, SESSION_ID, true, 100);
    }

    @Test
    void otherTenantAndNonParticipantCallersAreRefusedBeforeToolReads() {
        when(chatMapper.getAccessibleSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.list(SESSION_ID, false));

        when(workspaceService.getCurrentWorkspaceId()).thenReturn(99);
        when(workspaceService.getCurrentUserId()).thenReturn(77);
        when(chatMapper.getAccessibleSessionById(99, 77, SESSION_ID)).thenReturn(null);
        assertThrows(
                ResourceNotFoundException.class,
                () -> service.get(SESSION_ID, 29));
        verify(chatMapper, never()).listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100);
        verify(chatMapper, never()).getToolCallBySession(99, SESSION_ID, 29);
    }

    @Test
    void undoneAndMalformedRowsFailClosedWithoutExposingStoredJson() {
        AiChatToolCall undone = toolCall(
                32,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                22,
                "{\"undo\":{\"status\":\"undone\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        AiChatToolCall malformed = toolCall(
                33,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                23,
                null);
        malformed.setArgumentsJson("{\"tool\":\"create_note\",\"tier\":\"auto\","
                + "\"target\":{\"kind\":\"company\",\"id\":31}}");
        AiChatToolCall fractional = toolCall(
                35,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                24,
                null);
        fractional.setArgumentsJson("{\"tool\":\"create_note\",\"tier\":\"auto\","
                + "\"target\":{\"kind\":\"person\",\"id\":31.9}}");
        AiChatToolCall inconsistent = toolCall(
                36,
                USER_ID,
                "create_note",
                "auto",
                "failed",
                "person",
                31,
                25,
                "{\"undo\":{\"status\":\"undone\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(
                        undone, malformed, fractional, inconsistent));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(22, 25), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(2, result.size());
        assertEquals("undone", result.getFirst().status());
        assertFalse(result.getFirst().undoAvailable());
        assertEquals("failed", result.get(1).status());
    }

    @Test
    void requesterWithoutCurrentToolPermissionsCannotUndo() {
        AiChatToolCall toolCall = toolCall(
                37,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                26,
                "{\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of());
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(26), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertFalse(result.undoAvailable());
        assertEquals("2026-08-12T12:10:00Z", result.undoExpiresAt());
    }

    @Test
    void existingTagOutcomeIsReportedWithoutStoredTagData() {
        AiChatToolCall toolCall = toolCall(
                38,
                USER_ID,
                "add_tag",
                "auto",
                "executed",
                "person",
                31,
                27,
                "{\"outcome\":{\"changed\":false,\"tag\":\"private tag\"},"
                        + "\"undo\":{\"status\":\"unavailable\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(27), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("Tag was already present", result.outcomeSummary());
        assertFalse(result.outcomeSummary().contains("private"));
    }

    @Test
    void aTagIsNeverOfferedUndoEvenWhenItsStoredInverseSaysAvailable() {
        AiChatToolCall toolCall = toolCall(
                38,
                USER_ID,
                "add_tag",
                "auto",
                "executed",
                "person",
                31,
                27,
                "{\"outcome\":{\"status\":\"executed\",\"changed\":true},"
                        + "\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\",\"entityKind\":\"tag\","
                        + "\"entityId\":31,\"fingerprint\":\"present:5\",\"tagId\":5}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(27), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("executed", result.status());
        assertEquals("Tag added", result.outcomeSummary());
        assertEquals("2026-08-12T12:10:00Z", result.undoExpiresAt());
        assertFalse(result.undoAvailable());
    }

    @Test
    void aSharedParticipantStillReadsWhetherTheTagWasAddedButNotWhichTag() {
        AiChatToolCall toolCall = toolCall(
                38,
                USER_ID + 1,
                "add_tag",
                "auto",
                "executed",
                "person",
                31,
                27,
                "{\"outcome\":{\"status\":\"executed\",\"recordType\":\"person\","
                        + "\"tag\":\"Priority\",\"changed\":true},"
                        + "\"undo\":{\"status\":\"unavailable\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(27), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("Add an existing tag", result.requestSummary());
        assertEquals("Tag added", result.outcomeSummary());
        assertEquals(List.of(), result.outcomeValues());
        assertFalse(result.undoAvailable());
    }

    @Test
    void archivedSessionNeverAdvertisesUndo() {
        accessibleSession.setStatus("archived");
        AiChatToolCall toolCall = toolCall(
                39,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                28,
                "{\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(28), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertFalse(result.undoAvailable());
        assertEquals("2026-08-12T12:10:00Z", result.undoExpiresAt());
    }

    @Test
    void executedOwnerClearReportsRemoval() {
        AiChatToolCall toolCall = toolCall(
                40,
                USER_ID,
                "assign_owner",
                "confirm",
                "executed",
                "person",
                31,
                29,
                "{}");
        toolCall.setArgumentsJson("{\"tool\":\"assign_owner\",\"tier\":\"confirm\","
                + "\"restrictionEpoch\":1,\"target\":{\"kind\":\"person\",\"id\":31},"
                + "\"request\":{\"handle\":\"r1\",\"owner\":\"unassigned\"}}");
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(29), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.list(SESSION_ID, false).getFirst();

        assertEquals("Remove the current owner", result.requestSummary());
        assertEquals("Owner removed", result.outcomeSummary());
    }

    @Test
    void retainedAdminReadsDepartedAuthorCardsWithoutUndoAndRecordsMetadataAudit() {
        AiChatSession retained = retainedSession(44, "active");
        AiChatToolCall toolCall = toolCall(
                41,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                30,
                "{\"private\":\"stored result\",\"undo\":{"
                        + "\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(chatMapper.getRetainedSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID, List.of(USER_ID))).thenReturn(retained);
        when(chatMapper.listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100)).thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(30), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        AiAssistantToolCallReadDto result = service.listRetained(SESSION_ID, false).getFirst();

        assertEquals("Note created", result.outcomeSummary());
        assertFalse(result.undoAvailable());
        assertEquals("2026-08-12T12:10:00Z", result.undoExpiresAt());
        assertFalse(result.outcomeSummary().contains("stored result"));
        verify(workspaceService).requirePermission(
                WORKSPACE_ID, USER_ID, Permission.AI_SESSION_ADMIN);
        verify(sessionReadAudit).record(SESSION_ID, "retained");
    }

    @Test
    void retainedScopeRejectsActiveAuthorBeforeReadingCardsOrAuditing() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Admin", "ada-admin"),
                user(44, "Active Author", "active-author")));

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.listRetained(SESSION_ID, false));

        verify(chatMapper, never()).listToolCallsBySession(
                WORKSPACE_ID, SESSION_ID, false, 100);
        verify(sessionReadAudit, never()).record(SESSION_ID, "retained");
    }

    @Test
    void retainedScopeFailsClosedWhenDepartedAuthorRejoinsDuringRead() {
        AiChatSession retained = retainedSession(44, "active");
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(
                List.of(user(USER_ID, "Ada Admin", "ada-admin")),
                List.of(
                        user(USER_ID, "Ada Admin", "ada-admin"),
                        user(44, "Rejoined Author", "rejoined-author")));
        when(chatMapper.getRetainedSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID, List.of(USER_ID))).thenReturn(retained);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.getRetained(SESSION_ID, 41));

        verify(chatMapper, never()).getToolCallBySession(WORKSPACE_ID, SESSION_ID, 41);
        verify(sessionReadAudit, never()).record(SESSION_ID, "retained");
    }

    @Test
    void pendingOwnerProposalStatesTheExactBeforeAndAfterValues() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(50, 31, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(toolCall, 31, List.of(owned));

        AiAssistantToolCallReadDto.Change change =
                service.list(SESSION_ID, false).getFirst().change();

        assertEquals("owner", change.field());
        assertEquals("Ada Owner", change.currentValue());
        assertEquals("Grace Hopper", change.proposedValue());
        assertEquals("ready", change.state());
    }

    @Test
    void ownerProposalsRepresentBothClearingAndFirstTimeAssignment() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall clearing = ownerProposal(51, 31, "unassigned");
        AiChatToolCall assigning = ownerProposal(52, 32, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        Person unowned = person(32, "Alan Turing");
        unowned.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(clearing, assigning));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(51, 52), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31, 32)))
                .thenReturn(List.of(owned, unowned));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals("Ada Owner", result.getFirst().change().currentValue());
        assertNull(result.getFirst().change().proposedValue());
        assertEquals("ready", result.getFirst().change().state());
        assertNull(result.get(1).change().currentValue());
        assertEquals("Grace Hopper", result.get(1).change().proposedValue());
        assertEquals("ready", result.get(1).change().state());
    }

    @Test
    void proposalsThatWouldDoNothingOrNoLongerResolveSayWhichItIs() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner")));
        AiChatToolCall redundant = ownerProposal(53, 31, "Ada Owner");
        AiChatToolCall departed = ownerProposal(54, 32, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        Person other = person(32, "Alan Turing");
        other.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(redundant, departed));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(53, 54), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31, 32)))
                .thenReturn(List.of(owned, other));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals("unchanged", result.getFirst().change().state());
        assertEquals("unresolved", result.get(1).change().state());
        assertNull(result.get(1).change().proposedValue());
    }

    @Test
    void aRecordEditedSinceTheProposalIsReportedAsChanged() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(55, 31, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:59:30.000000");
        stubPending(toolCall, 31, List.of(owned));

        assertEquals("recordChanged", service.list(SESSION_ID, false)
                .getFirst().change().state());
    }

    @Test
    void aSecondPrecisionEditInTheProposalsOwnSecondIsReportedAsChanged() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(57, 31, "Grace Hopper");
        toolCall.setCreatedAt("2026-08-12 11:59:00.400000");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:59:00");
        stubPending(toolCall, 31, List.of(owned));

        assertEquals("recordChanged", service.list(SESSION_ID, false)
                .getFirst().change().state());
    }

    @Test
    void aRecordLastEditedTheSecondBeforeTheProposalStaysApplicable() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(58, 31, "Grace Hopper");
        toolCall.setCreatedAt("2026-08-12 11:59:00.400000");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:58:59");
        stubPending(toolCall, 31, List.of(owned));

        assertEquals("ready", service.list(SESSION_ID, false).getFirst().change().state());
    }

    @Test
    void anUnreadableTimestampNeverInventsAChangedRecord() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(56, 31, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("not a timestamp");
        stubPending(toolCall, 31, List.of(owned));

        assertEquals("ready", service.list(SESSION_ID, false).getFirst().change().state());
    }

    @Test
    void aViewerWhoLostTheUpdatePermissionSeesTheChangeAsUnapplicable() {
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of());
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall toolCall = ownerProposal(57, 31, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(toolCall, 31, List.of(owned));

        assertEquals("permissionLost", service.list(SESSION_ID, false)
                .getFirst().change().state());
    }

    @Test
    void pendingDealStageProposalStatesTheDealsCurrentStage() {
        AiChatToolCall toolCall = toolCall(
                58, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 58, null);
        Deal deal = new Deal();
        deal.setId(41);
        deal.setName("Acme renewal");
        deal.setPipelineId(3);
        deal.setStageId(9);
        deal.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(58), 100)).thenReturn(List.of());
        when(dealMapper.getByIds(WORKSPACE_ID, List.of(41))).thenReturn(List.of(deal));
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));

        AiAssistantToolCallReadDto.Change change =
                service.list(SESSION_ID, false).getFirst().change();

        assertEquals("stage", change.field());
        assertEquals("Negotiation", change.currentValue());
        assertEquals("Won", change.proposedValue());
        assertEquals("ready", change.state());
    }

    @Test
    void beforeValuesNeverReachASharedViewerOrAnUnreadableTarget() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall otherMembersProposal = ownerProposal(59, 31, "Grace Hopper");
        otherMembersProposal.setRequestedByUserId(99);
        AiChatToolCall restrictedTarget = ownerProposal(60, 32, "Grace Hopper");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(otherMembersProposal, restrictedTarget));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(59, 60), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31, 32)))
                .thenReturn(List.of(owned));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertNull(result.getFirst().change());
        assertTrue(result.getFirst().outcomeValues().isEmpty());
        assertNull(result.get(1).change());
        assertNull(result.get(1).target().id());
    }

    @Test
    void completedActionsReportOnlyAllowlistedScreenedResultValues() {
        AiChatToolCall toolCall = toolCall(
                61,
                USER_ID,
                "create_task",
                "auto",
                "executed",
                "person",
                31,
                61,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"recordType\":\"task\",\"description\":\"Follow up on renewal\","
                        + "\"dueDate\":\"2026-08-22\",\"assignee\":\"private assignee\"},"
                        + "\"undo\":{\"status\":\"available\","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\","
                        + "\"fingerprint\":\"private fingerprint\"}}");
        AiChatToolCall screened = toolCall(
                62,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                62,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"title\":\"Diagnosis follow-up\",\"visibility\":\"private\"}}");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall, screened));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(61, 62), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals(
                List.of("description", "dueDate"),
                result.getFirst().outcomeValues().stream()
                        .map(AiAssistantToolCallReadDto.OutcomeValue::field).toList());
        assertEquals("Follow up on renewal", result.getFirst().outcomeValues()
                .getFirst().value());
        assertEquals("2026-08-22", result.getFirst().outcomeValues().get(1).value());
        assertNull(result.getFirst().change());
        assertEquals(
                List.of("visibility"),
                result.get(1).outcomeValues().stream()
                        .map(AiAssistantToolCallReadDto.OutcomeValue::field).toList());
    }

    @Test
    void removingADepartedMembersOwnershipIsAppliableAndSaysWhoCannotBeNamed() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner")));
        AiChatToolCall toolCall = ownerProposal(63, 31, "unassigned");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(77);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(toolCall, 31, List.of(owned));

        AiAssistantToolCallReadDto.Change change =
                service.list(SESSION_ID, false).getFirst().change();

        assertEquals("ready", change.state());
        assertNull(change.currentValue());
        assertTrue(change.currentValueUnresolved());
        assertNull(change.proposedValue());
    }

    @Test
    void removingOwnershipFromARecordThatHasNoneStillReadsAsUnchanged() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner")));
        AiChatToolCall toolCall = ownerProposal(64, 31, "unassigned");
        Person unowned = person(31, "Ada Lovelace");
        unowned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(toolCall, 31, List.of(unowned));

        AiAssistantToolCallReadDto.Change change =
                service.list(SESSION_ID, false).getFirst().change();

        assertEquals("unchanged", change.state());
        assertNull(change.currentValue());
        assertFalse(change.currentValueUnresolved());
    }

    @Test
    void aStageProposalIsUnchangedOnTheDealsOwnStageIdRatherThanItsName() {
        AiChatToolCall toolCall = toolCall(
                65, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 65, null);
        Deal deal = new Deal();
        deal.setId(41);
        deal.setName("Acme renewal");
        deal.setPipelineId(3);
        deal.setStageId(10);
        deal.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(65), 100)).thenReturn(List.of());
        when(dealMapper.getByIds(WORKSPACE_ID, List.of(41))).thenReturn(List.of(deal));
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));

        AiAssistantToolCallReadDto.Change change =
                service.list(SESSION_ID, false).getFirst().change();

        assertEquals("unchanged", change.state());
        assertEquals("Won", change.currentValue());
        assertFalse(change.currentValueUnresolved());
    }

    @Test
    void completedCreationsNameTheRecordTheyMadeAndNothingElse() {
        AiChatToolCall created = toolCall(
                66,
                USER_ID,
                "create_task",
                "auto",
                "executed",
                "person",
                31,
                66,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"description\":\"Follow up on renewal\"},"
                        + "\"undo\":{\"status\":\"available\",\"entityKind\":\"task\","
                        + "\"entityId\":74,\"expiresAt\":\"2026-08-12T12:10:00Z\","
                        + "\"fingerprint\":\"private fingerprint\"}}");
        AiChatToolCall tagged = toolCall(
                67,
                USER_ID,
                "add_tag",
                "auto",
                "executed",
                "person",
                31,
                67,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\",\"changed\":true},"
                        + "\"undo\":{\"status\":\"unavailable\",\"entityKind\":\"tag\","
                        + "\"entityId\":31,\"tagId\":5}}");
        AiChatToolCall undone = toolCall(
                68,
                USER_ID,
                "create_note",
                "auto",
                "executed",
                "person",
                31,
                68,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\"},"
                        + "\"undo\":{\"status\":\"undone\",\"entityKind\":\"note\","
                        + "\"entityId\":75,\"expiresAt\":\"2026-08-12T12:10:00Z\"}}");
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(created, tagged, undone));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(66, 67, 68), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));
        when(taskMapper.getVisibleIdsIn(WORKSPACE_ID, List.of(74))).thenReturn(List.of(74));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals("task", result.getFirst().createdRecord().kind());
        assertEquals(74, result.getFirst().createdRecord().id());
        assertNull(result.get(1).createdRecord());
        assertEquals("undone", result.get(2).status());
        assertNull(result.get(2).createdRecord());
    }

    @Test
    void aCreatedRecordDeletedSinceTheActionIsNoLongerOfferedAsALink() {
        AiChatToolCall deleted = createdActivityCall(69, 76);
        AiChatToolCall live = createdActivityCall(70, 77);
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(deleted, live));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(69, 70), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(31)))
                .thenReturn(List.of(person(31, "Ada Lovelace")));
        when(activityMapper.getVisibleIdsIn(WORKSPACE_ID, List.of(76, 77)))
                .thenReturn(List.of(77));

        List<AiAssistantToolCallReadDto> result = service.list(SESSION_ID, false);

        assertEquals("executed", result.getFirst().status());
        assertNull(result.getFirst().createdRecord());
        assertEquals("Ada Lovelace", result.getFirst().target().label());
        assertEquals("activity", result.get(1).createdRecord().kind());
        assertEquals(77, result.get(1).createdRecord().id());
    }

    private static AiChatToolCall createdActivityCall(int id, int activityId) {
        return toolCall(
                id,
                USER_ID,
                "create_activity",
                "auto",
                "executed",
                "person",
                31,
                id,
                "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                        + "\"subject\":\"Renewal call\"},"
                        + "\"undo\":{\"status\":\"available\",\"entityKind\":\"activity\","
                        + "\"entityId\":" + activityId + ","
                        + "\"expiresAt\":\"2026-08-12T12:10:00Z\","
                        + "\"fingerprint\":\"private fingerprint\"}}");
    }

    @Test
    void everyDeclaredToolGivesAParticipantAndARetainedAdminOnlyItsGenericSummaries() {
        List<Review> seen = new ArrayList<>();
        AiAssistantToolCallReadService echoing = service(AiAssistantDeclaredWriteTools.tools()
                .stream()
                .map(tool -> (AiAssistantWriteTool) new EchoingTool(tool, seen))
                .toList());
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(declaredToolCards(99));
        List<AiAssistantToolCallReadDto> participant = echoing.list(SESSION_ID, false);
        when(chatMapper.getRetainedSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID, List.of(USER_ID)))
                .thenReturn(retainedSession(44, "active"));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(declaredToolCards(44));
        List<AiAssistantToolCallReadDto> retained = echoing.listRetained(SESSION_ID, false);

        assertEquals(4 * AiAssistantDeclaredWriteTools.tools().size(),
                participant.size() + retained.size());
        for (AiAssistantToolCallReadDto card : concat(participant, retained)) {
            assertEquals(WITHHELD_ECHO, card.requestSummary(), card.toolName());
            if ("executed".equals(card.status())) {
                assertEquals(WITHHELD_ECHO, card.outcomeSummary(), card.toolName());
            }
            assertNull(card.change());
        }
        assertFalse(seen.isEmpty());
        for (Review review : seen) {
            assertFalse(review.detailsReadable());
            assertNull(review.target());
            assertNull(review.request());
            assertNull(review.outcome());
            assertTrue(review.members().isEmpty());
            assertTrue(review.stages().isEmpty());
            assertTrue(review.tags().isEmpty());
            assertTrue(review.targetTags().isEmpty());
            assertNull(review.pinnedResolutionId());
            assertNull(review.pinnedPrincipalIds());
        }
        verify(pipelineMapper, never()).getAllStages(WORKSPACE_ID);
        verifyNoInteractions(tagMapper);
    }

    @Test
    void aViewerWhoMayNotReadTheDetailsIsHandedOnlyTheBooleanFlagsTheToolShares() {
        AiAssistantToolCallReadService flagging = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                stageTool(),
                new AiAssistantAddTagWriteTool(
                        mock(TagService.class),
                        mock(PersonService.class),
                        mock(CompanyService.class),
                        mock(DealService.class)) {
                    @Override
                    public Set<String> sharedOutcomeFlags() {
                        return Set.of("changed", "tag", "recordType", "missing");
                    }

                    @Override
                    public String outcomeSummary(Review review) {
                        return String.valueOf(review.outcome());
                    }
                },
                removeTagTool(),
                ownerTool()));
        stubVisibleDeal();
        String flagged = "{\"tier\":\"auto\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"deal\",\"tag\":\"Secret\",\"changed\":false,"
                + "\"archived\":true}}";
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(
                        toolCall(71, 99, "add_tag", "auto", "executed", "deal", 41, 71, flagged),
                        toolCall(72, 99, "add_tag", "auto", "executed", "deal", 41, 72,
                                flagged.replace("\"changed\":false", "\"changed\":\"no\"")),
                        toolCall(73, USER_ID, "add_tag", "auto", "executed", "deal", 41, 73,
                                flagged)));

        List<AiAssistantToolCallReadDto> cards = flagging.list(SESSION_ID, false);

        assertEquals("{\"changed\":false}", cards.get(0).outcomeSummary());
        assertEquals("null", cards.get(1).outcomeSummary());
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"deal\",\"tag\":\"Secret\","
                        + "\"changed\":false,\"archived\":true}",
                cards.get(2).outcomeSummary());
    }

    @Test
    void aViewerWhoMayNotReadTheDetailsIsHandedOnlyTheRequestFlagsTheToolShares() {
        AiAssistantToolCallReadService flagging = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                stageTool(),
                tagTool(),
                removeTagTool(),
                new AiAssistantAssignOwnerWriteTool(
                        mock(PersonService.class),
                        mock(CompanyService.class),
                        mock(DealService.class)) {
                    @Override
                    public String requestSummary(Review review) {
                        return String.valueOf(review.request());
                    }

                    @Override
                    public String outcomeSummary(Review review) {
                        return String.valueOf(review.request());
                    }
                }));
        stubVisibleDeal();
        String removed = "{\"tier\":\"confirm\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"deal\",\"owner\":\"unassigned\"}}";
        AiChatToolCall removal = toolCall(
                75, 99, "assign_owner", "confirm", "executed", "deal", 41, 75, removed);
        removal.setArgumentsJson(removal.getArgumentsJson().replace(" Ada Owner ", "Unassigned"));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(
                        removal,
                        toolCall(76, 99, "assign_owner", "confirm", "proposed", "deal", 41, 76,
                                null),
                        toolCall(77, USER_ID, "assign_owner", "confirm", "proposed", "deal", 41,
                                77, null)));

        List<AiAssistantToolCallReadDto> cards = flagging.list(SESSION_ID, false);

        assertEquals("{\"removesOwner\":true}", cards.get(0).requestSummary());
        assertEquals("{\"removesOwner\":true}", cards.get(0).outcomeSummary());
        assertEquals("{\"removesOwner\":false}", cards.get(1).requestSummary());
        assertEquals(
                "{\"handle\":\"r1\",\"owner\":\" Ada Owner \"}", cards.get(2).requestSummary());
    }

    @Test
    void aParticipantStillReadsWhetherTheOwnerWasRemovedButNeverWhoWasNamed() {
        stubVisibleDeal();
        String removed = "{\"tier\":\"confirm\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"deal\",\"owner\":\"unassigned\"}}";
        String assigned = "{\"tier\":\"confirm\",\"outcome\":{\"status\":\"executed\","
                + "\"recordType\":\"deal\",\"owner\":\"Ada Owner\"}}";
        AiChatToolCall removal = toolCall(
                78, 99, "assign_owner", "confirm", "executed", "deal", 41, 78, removed);
        removal.setArgumentsJson(removal.getArgumentsJson().replace(" Ada Owner ", "Unassigned"));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(
                        removal,
                        toolCall(79, 99, "assign_owner", "confirm", "executed", "deal", 41, 79,
                                assigned)));

        List<AiAssistantToolCallReadDto> cards = service.list(SESSION_ID, false);

        assertEquals("Assign an owner", cards.get(0).requestSummary());
        assertEquals("Owner removed", cards.get(0).outcomeSummary());
        assertEquals("Assign an owner", cards.get(1).requestSummary());
        assertEquals("Owner assigned", cards.get(1).outcomeSummary());
        assertEquals(List.of(), cards.get(1).outcomeValues());
        verify(workspaceService, never()).getMembers(WORKSPACE_ID);
    }

    @Test
    void theRequesterIsGivenTheDetailedSummaryTheSameToolWithholdsFromOthers() {
        List<Review> seen = new ArrayList<>();
        AiAssistantToolCallReadService echoing = service(AiAssistantDeclaredWriteTools.tools()
                .stream()
                .map(tool -> (AiAssistantWriteTool) new EchoingTool(tool, seen))
                .toList());
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(declaredToolCards(USER_ID));

        for (AiAssistantToolCallReadDto card : echoing.list(SESSION_ID, false)) {
            assertTrue(card.requestSummary().contains("Acme renewal"), card.toolName());
            assertTrue(card.requestSummary().contains("secret request"), card.toolName());
        }
        assertFalse(seen.isEmpty());
        for (Review review : seen) {
            assertTrue(review.detailsReadable());
            assertEquals(Integer.valueOf(9), review.pinnedResolutionId());
            assertEquals(List.of(55), review.pinnedPrincipalIds());
        }
    }

    /**
     * A pinned stage proposal names the stage it was reviewed against, and once that stage is
     * renamed away and the deal's own stage renamed into the same name it is unresolved and says no
     * stage, exactly as its approval would refuse, where an unpinned card would call it a no-op.
     */
    @Test
    void aPinnedStageProposalNamesOnlyThePinnedStage() {
        AiChatToolCall reviewed = pinned(
                toolCall(81, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 81,
                        null),
                ",\"resolution\":{\"field\":\"stage\",\"id\":10},\"principals\":[]");
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(reviewed));
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));

        AiAssistantToolCallReadDto ready = service.list(SESSION_ID, false).getFirst();

        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Won"), stage(10, 3, "Closed")));
        AiAssistantToolCallReadDto drifted = service.list(SESSION_ID, false).getFirst();

        assertEquals("Change deal stage to: Won", ready.requestSummary());
        assertEquals("Won", ready.change().proposedValue());
        assertEquals("ready", ready.change().state());
        assertEquals("Change the deal stage", drifted.requestSummary());
        assertNull(drifted.change().proposedValue());
        assertEquals("unresolved", drifted.change().state());
        assertEquals("Won", drifted.change().currentValue());
    }

    /**
     * A tag removal card reviews its pinned tag against the tags its record holds, read for the
     * whole page in one batch: a held tag is removed, an absent one would change nothing, and a
     * tag deleted and re-created under the reviewed name is unresolved, exactly as its approval
     * would refuse, while the record's own tag under that name is still shown as what it holds.
     */
    @Test
    void aTagRemovalCardReviewsItsPinnedTagAgainstTheTagsItsRecordHolds() {
        AiChatToolCall held = pinned(
                toolCall(83, USER_ID, "remove_tag", "confirm", "proposed", "deal", 41, 83, null),
                ",\"resolution\":{\"field\":\"tag\",\"id\":9},\"principals\":[]");
        AiChatToolCall sameRecord = pinned(
                toolCall(84, USER_ID, "remove_tag", "confirm", "proposed", "deal", 41, 84, null),
                ",\"resolution\":{\"field\":\"tag\",\"id\":9},\"principals\":[]");
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(held, sameRecord));
        when(tagMapper.getAllTags(WORKSPACE_ID))
                .thenReturn(List.of(tag(8, "Prospect"), tag(9, "Priority")));
        when(tagMapper.getTagsForRecords(WORKSPACE_ID, "deal", List.of(41)))
                .thenReturn(List.of(new RecordTag(41, 9, "Priority")));

        List<AiAssistantToolCallReadDto> cards = service.list(SESSION_ID, false);

        assertEquals(2, cards.size());
        AiAssistantToolCallReadDto ready = cards.getFirst();
        assertEquals("Remove tag: Priority", ready.requestSummary());
        assertEquals("tag", ready.change().field());
        assertEquals("Priority", ready.change().currentValue());
        assertNull(ready.change().proposedValue());
        assertEquals("ready", ready.change().state());
        verify(tagMapper).getAllTags(WORKSPACE_ID);
        verify(tagMapper).getTagsForRecords(WORKSPACE_ID, "deal", List.of(41));

        when(tagMapper.getTagsForRecords(WORKSPACE_ID, "deal", List.of(41)))
                .thenReturn(List.of(new RecordTag(41, 8, "Prospect")));
        AiAssistantToolCallReadDto unchanged = service.list(SESSION_ID, false).getFirst();
        assertEquals("Remove tag: Priority", unchanged.requestSummary());
        assertNull(unchanged.change().currentValue());
        assertEquals("unchanged", unchanged.change().state());

        when(tagMapper.getAllTags(WORKSPACE_ID))
                .thenReturn(List.of(tag(8, "Prospect"), tag(12, "Priority")));
        when(tagMapper.getTagsForRecords(WORKSPACE_ID, "deal", List.of(41)))
                .thenReturn(List.of(new RecordTag(41, 12, "Priority")));
        AiAssistantToolCallReadDto drifted = service.list(SESSION_ID, false).getFirst();
        assertEquals("Remove a tag", drifted.requestSummary());
        assertEquals("Priority", drifted.change().currentValue());
        assertNull(drifted.change().proposedValue());
        assertEquals("unresolved", drifted.change().state());
    }

    /**
     * Issue 1865 on the card: the pinned member was offboarded and another member took the same
     * display name. The card says no member rather than naming one the approver never reviewed.
     */
    @Test
    void aPinnedOwnerProposalNamesOnlyThePinnedMember() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        AiChatToolCall reviewed = pinned(
                ownerProposal(82, 31, "Grace Hopper"), ",\"principals\":[55]");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(reviewed, 31, List.of(owned));

        AiAssistantToolCallReadDto ready = service.list(SESSION_ID, false).getFirst();

        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(56, "Grace Hopper", "grace-hopper-2")));
        AiAssistantToolCallReadDto drifted = service.list(SESSION_ID, false).getFirst();

        assertEquals("Assign owner: Grace Hopper", ready.requestSummary());
        assertEquals("Grace Hopper", ready.change().proposedValue());
        assertEquals("ready", ready.change().state());
        assertEquals("Assign an owner", drifted.requestSummary());
        assertNull(drifted.change().proposedValue());
        assertEquals("unresolved", drifted.change().state());
        assertEquals("Ada Owner", drifted.change().currentValue());
    }

    /**
     * A pinned proposal is recognised by its principals, not by its resolution: a stage proposal
     * pinned with no stage, or with a member, and an owner proposal pinned with a value, are each
     * refused by every approval, so each card is unresolved and arms nothing, even though its name
     * still resolves exactly as an unpinned card would show it ready.
     */
    @Test
    void aPinnedProposalWhoseApprovalCanOnlyRefuseIsUnresolved() {
        stubVisibleDeal();
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));
        for (String pins : List.of(",\"principals\":[]",
                ",\"resolution\":{\"field\":\"stage\",\"id\":10},\"principals\":[55]")) {
            when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                    .thenReturn(List.of(pinned(
                            toolCall(85, USER_ID, "change_deal_stage", "confirm", "proposed",
                                    "deal", 41, 85, null),
                            pins)));

            AiAssistantToolCallReadDto card = service.list(SESSION_ID, false).getFirst();

            assertEquals("Change the deal stage", card.requestSummary(), pins);
            assertNull(card.change().proposedValue(), pins);
            assertEquals("unresolved", card.change().state(), pins);
        }
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Grace Hopper", "grace-hopper")));
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(
                pinned(ownerProposal(86, 31, "Grace Hopper"),
                        ",\"resolution\":{\"field\":\"owner\",\"id\":55},"
                                + "\"principals\":[55]"),
                31, List.of(owned));

        AiAssistantToolCallReadDto owner = service.list(SESSION_ID, false).getFirst();

        assertEquals("Assign an owner", owner.requestSummary());
        assertNull(owner.change().proposedValue());
        assertEquals("unresolved", owner.change().state());
    }

    /** Pins only the framework writes that do not parse leave no card at all. */
    @Test
    void aProposalWhosePinsDoNotParseLeavesNoCard() {
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(
                        pinned(toolCall(83, USER_ID, "change_deal_stage", "confirm", "proposed",
                                "deal", 41, 83, null),
                                ",\"resolution\":{\"field\":\"stage\",\"id\":10}"),
                        pinned(toolCall(84, USER_ID, "assign_owner", "confirm", "proposed",
                                "deal", 41, 84, null),
                                ",\"principals\":[\"55\"]")));

        assertEquals(List.of(), service.list(SESSION_ID, false));
    }

    @Test
    void aDetailedSummaryTheSpecialCareScreenExcludesFallsBackToTheGenericOne() {
        AiChatToolCall toolCall = toolCall(
                66, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 66, null);
        toolCall.setArgumentsJson(toolCall.getArgumentsJson().replace("Won", "Diagnosis"));
        stubVisibleDeal();
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(11, 3, "Diagnosis")));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall));

        AiAssistantToolCallReadDto card = service.list(SESSION_ID, false).getFirst();

        assertEquals("Change the deal stage", card.requestSummary());
        assertEquals("Diagnosis", card.change().proposedValue());
    }

    @Test
    void anOwnerSummaryNamesTheResolvedMemberWhateverTheSpecialCareScreenWouldSay() {
        when(workspaceService.getMembers(WORKSPACE_ID)).thenReturn(List.of(
                user(USER_ID, "Ada Owner", "ada-owner"),
                user(55, "Christian Weber", "cweber")));
        AiChatToolCall toolCall = ownerProposal(53, 31, "christian weber");
        Person owned = person(31, "Ada Lovelace");
        owned.setOwnerId(USER_ID);
        owned.setUpdatedAt("2026-08-12 11:00:00.000000");
        stubPending(toolCall, 31, List.of(owned));
        AiAssistantToolCallReadService screening = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                stageTool(),
                tagTool(),
                removeTagTool(),
                new AiAssistantAssignOwnerWriteTool(
                        mock(PersonService.class),
                        mock(CompanyService.class),
                        mock(DealService.class)) {
                    @Override
                    public boolean screensDetailedRequestSummary() {
                        return true;
                    }
                }));

        AiAssistantToolCallReadDto card = service.list(SESSION_ID, false).getFirst();
        AiAssistantToolCallReadDto screened = screening.list(SESSION_ID, false).getFirst();

        assertEquals("Assign owner: Christian Weber", card.requestSummary());
        assertEquals("Christian Weber", card.change().proposedValue());
        assertEquals("Assign an owner", screened.requestSummary());
        assertEquals("Christian Weber", screened.change().proposedValue());
    }

    @Test
    void theReadPathEvaluatesOnlyTheRequestFlagsTheRegistryReadAtStartup() {
        List<String> asked = new ArrayList<>();
        AiAssistantToolCallReadService flagging = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                stageTool(),
                tagTool(),
                removeTagTool(),
                new AiAssistantAssignOwnerWriteTool(
                        mock(PersonService.class),
                        mock(CompanyService.class),
                        mock(DealService.class)) {
                    @Override
                    public Map<String, SharedRequestFlag> sharedRequestFlags() {
                        asked.add("flags");
                        return asked.size() == 1
                                ? super.sharedRequestFlags()
                                : Map.of("namesAda", new SharedRequestFlag("owner", "Ada Owner"));
                    }

                    @Override
                    public String requestSummary(Review review) {
                        return String.valueOf(review.request());
                    }
                }));
        stubVisibleDeal();
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall(
                        80, 99, "assign_owner", "confirm", "proposed", "deal", 41, 80, null)));

        List<AiAssistantToolCallReadDto> cards = flagging.list(SESSION_ID, false);

        assertEquals("{\"removesOwner\":false}", cards.getFirst().requestSummary());
        assertEquals(List.of("flags"), asked);
    }

    @Test
    void aProposalIsShownApplicableOnlyWhenTheViewerHoldsEveryPermissionTheToolDeclares() {
        AiAssistantToolCallReadService declaringMore = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                new AiAssistantChangeDealStageWriteTool(
                        mock(DealService.class), mock(PipelineService.class)) {
                    @Override
                    public Set<Permission> requiredPermissions(String targetKind) {
                        return Set.of(Permission.DEAL_UPDATE, Permission.DEAL_DELETE);
                    }
                },
                tagTool(),
                removeTagTool(),
                ownerTool()));
        stubVisibleDeal();
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall(
                        67, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 67,
                        null)));

        assertEquals("ready", service.list(SESSION_ID, false).getFirst().change().state());
        assertEquals(
                "permissionLost",
                declaringMore.list(SESSION_ID, false).getFirst().change().state());
    }

    @Test
    void aToolIsHandedOnlyTheBatchedInputsItDeclares() {
        AiAssistantToolCallReadService undeclared = service(List.of(
                activityTool(),
                noteTool(),
                new AiAssistantCreateTaskWriteTool(
                        mock(TaskService.class),
                        mock(AiAssistantDateResolver.class),
                        JsonMapper.builder().build()),
                new AiAssistantChangeDealStageWriteTool(
                        mock(DealService.class), mock(PipelineService.class)) {
                    @Override
                    public Set<ReviewInput> reviewInputs() {
                        return Set.of();
                    }
                },
                tagTool(),
                removeTagTool(),
                ownerTool()));
        stubVisibleDeal();
        when(pipelineMapper.getAllStages(WORKSPACE_ID)).thenReturn(List.of(
                stage(9, 3, "Negotiation"), stage(10, 3, "Won")));
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall(
                        68, USER_ID, "change_deal_stage", "confirm", "proposed", "deal", 41, 68,
                        null)));

        AiAssistantToolCallReadDto.Change change =
                undeclared.list(SESSION_ID, false).getFirst().change();

        assertEquals("unresolved", change.state());
        verify(pipelineMapper, never()).getAllStages(WORKSPACE_ID);
        verify(workspaceService, never()).getMembers(WORKSPACE_ID);
    }

    private void stubVisibleDeal() {
        Deal deal = new Deal();
        deal.setId(41);
        deal.setName("Acme renewal");
        deal.setPipelineId(3);
        deal.setStageId(9);
        deal.setUpdatedAt("2026-08-12 11:00:00.000000");
        when(dealMapper.getByIds(WORKSPACE_ID, List.of(41))).thenReturn(List.of(deal));
    }

    /**
     * A proposed and an executed card for every declared tool, all on deal 41 and carrying record
     * values a viewer who may not read the details must never be shown.
     */
    private static List<AiChatToolCall> declaredToolCards(int requestedByUserId) {
        List<AiChatToolCall> cards = new ArrayList<>();
        int id = 70;
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            String tier = tool.tier().name().toLowerCase();
            for (String status : List.of("proposed", "executed")) {
                AiChatToolCall card = toolCall(
                        id, requestedByUserId, tool.name(), tier, status, "deal", 41, id,
                        "executed".equals(status)
                                ? "{\"tier\":\"" + tier + "\",\"outcome\":{\"status\":\"executed\","
                                        + "\"recordType\":\"deal\","
                                        + "\"description\":\"secret outcome\","
                                        + "\"stage\":\"secret outcome\"}}"
                                : null);
                card.setArgumentsJson("{\"tool\":\"" + tool.name() + "\",\"tier\":\"" + tier
                        + "\",\"restrictionEpoch\":1,\"target\":{\"kind\":\"deal\",\"id\":41},"
                        + "\"request\":{\"handle\":\"r1\",\"stage\":\"secret request\","
                        + "\"description\":\"secret request\",\"owner\":\"secret request\","
                        + "\"tag\":\"secret request\"},"
                        + "\"resolution\":{\"field\":\"stage\",\"id\":9},\"principals\":[55]}");
                cards.add(card);
                id++;
            }
        }
        return cards;
    }

    private static List<AiAssistantToolCallReadDto> concat(
            List<AiAssistantToolCallReadDto> first, List<AiAssistantToolCallReadDto> second) {
        List<AiAssistantToolCallReadDto> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static final String WITHHELD_ECHO = "echo null null null null 0 0";

    /**
     * A declared tool whose summaries echo every record value its review carries, so a card that
     * shows none of them proves the framework withheld them rather than the tool declining to.
     */
    private record EchoingTool(AiAssistantWriteTool delegate, List<Review> seen)
            implements AiAssistantWriteTool {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public ToolTier tier() {
            return delegate.tier();
        }

        @Override
        public Class<? extends AiAssistantWriteToolRequest> requestType() {
            return delegate.requestType();
        }

        @Override
        public Set<String> acceptedTargetKinds() {
            return delegate.acceptedTargetKinds();
        }

        @Override
        public Set<String> declaredWritableFields() {
            return delegate.declaredWritableFields();
        }

        @Override
        public Set<Permission> requiredPermissions(String targetKind) {
            return delegate.requiredPermissions(targetKind);
        }

        @Override
        public Lock lock(String targetKind) {
            return delegate.lock(targetKind);
        }

        @Override
        public List<PrincipalRequest> principals(
                AiAssistantWriteToolRequest request, MemberDirectory directory) {
            return delegate.principals(request, directory);
        }

        @Override
        public Outcome apply(Execution execution) {
            return delegate.apply(execution);
        }

        @Override
        public boolean inverseAvailable() {
            return delegate.inverseAvailable();
        }

        @Override
        public Set<ReviewInput> reviewInputs() {
            return Set.of(ReviewInput.MEMBERS, ReviewInput.STAGES);
        }

        @Override
        public Diff diff(Review review) {
            seen.add(review);
            return null;
        }

        @Override
        public String requestSummary(Review review) {
            return echo(review);
        }

        @Override
        public String outcomeSummary(Review review) {
            return echo(review);
        }

        @Override
        public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
            return delegate.modelOutcome(storedOutcome);
        }

        @Override
        public List<String> memberOutcomeFields() {
            return delegate.memberOutcomeFields();
        }

        private String echo(Review review) {
            seen.add(review);
            return "echo " + review.requestText("stage") + " "
                    + (review.target() == null ? null : review.target().label()) + " "
                    + review.outcome() + " "
                    + review.requestText("description") + " "
                    + review.members().size() + " "
                    + review.stages().size();
        }
    }

    private void stubPending(AiChatToolCall toolCall, int personId, List<Person> people) {
        when(chatMapper.listToolCallsBySession(WORKSPACE_ID, SESSION_ID, false, 100))
                .thenReturn(List.of(toolCall));
        when(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                WORKSPACE_ID, SESSION_ID, List.of(toolCall.getId()), 100)).thenReturn(List.of());
        when(personMapper.getByIds(WORKSPACE_ID, List.of(personId))).thenReturn(people);
    }

    /**
     * @param toolCall a stored proposal as it was written before pinning
     * @param pins the pin siblings a proposal prepared now carries after its request
     * @return the same proposal carrying those pins
     */
    private static Tag tag(int id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    private static AiChatToolCall pinned(AiChatToolCall toolCall, String pins) {
        String stored = toolCall.getArgumentsJson();
        toolCall.setArgumentsJson(stored.substring(0, stored.length() - 1) + pins + "}");
        return toolCall;
    }

    private static AiChatToolCall ownerProposal(int id, int personId, String owner) {
        AiChatToolCall toolCall = toolCall(
                id, USER_ID, "assign_owner", "confirm", "proposed", "person", personId, id, null);
        toolCall.setArgumentsJson("{\"tool\":\"assign_owner\",\"tier\":\"confirm\","
                + "\"restrictionEpoch\":1,\"target\":{\"kind\":\"person\",\"id\":" + personId
                + "},\"request\":{\"handle\":\"r1\",\"owner\":\"" + owner + "\"}}");
        return toolCall;
    }

    private static Stage stage(int id, int pipelineId, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(pipelineId);
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        stage.setPipeline(pipeline);
        return stage;
    }

    private static AiChatToolCall toolCall(
            int id,
            Integer requestedByUserId,
            String tool,
            String tier,
            String status,
            String targetKind,
            int targetId,
            int turnId,
            String resultJson) {
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setId(id);
        toolCall.setWorkspaceId(WORKSPACE_ID);
        toolCall.setMessageId(80 + id);
        toolCall.setSessionId(SESSION_ID);
        toolCall.setRequestedByUserId(requestedByUserId);
        toolCall.setToolName(tool);
        toolCall.setStatus(status);
        String request = switch (tool) {
            case "assign_owner" -> "{\"handle\":\"r1\",\"owner\":\" Ada Owner \"}";
            case "change_deal_stage" -> "{\"handle\":\"r1\",\"stage\":\"Won\"}";
            case "remove_tag" -> "{\"handle\":\"r1\",\"tag\":\"priority\"}";
            default -> "{\"handle\":\"r1\"}";
        };
        toolCall.setArgumentsJson("{\"tool\":\"" + tool + "\",\"tier\":\"" + tier
                + "\",\"restrictionEpoch\":1,\"target\":{\"kind\":\""
                + targetKind + "\",\"id\":" + targetId
                + "},\"request\":" + request + "}");
        toolCall.setResultJson(resultJson);
        toolCall.setIdempotencyKey("turn-" + turnId + "-step-1");
        toolCall.setCreatedAt("2026-08-12 11:59:00.000000");
        toolCall.setUpdatedAt("2026-08-12 12:00:00.000000");
        toolCall.setExecutedAt("2026-08-12 12:00:00.000000");
        return toolCall;
    }

    private static AiChatMessage assistantMessage(int id, int turnId) {
        AiChatMessage message = new AiChatMessage();
        message.setId(id);
        message.setAuthorKind("assistant");
        message.setStructuredJson("{\"turnId\":" + turnId + ",\"citations\":[]}");
        return message;
    }

    private static Person person(int id, String name) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        return person;
    }

    private static AiChatSession retainedSession(Integer createdByUserId, String status) {
        AiChatSession session = new AiChatSession();
        session.setId(SESSION_ID);
        session.setCreatedByUserId(createdByUserId);
        session.setStatus(status);
        return session;
    }

    private static User user(int id, String displayName, String username) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setUsername(username);
        return user;
    }
}
