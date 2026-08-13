package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
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
    private AiAssistantSessionReadAudit sessionReadAudit;
    private AiChatSession accessibleSession;
    private AiAssistantToolCallReadService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        workspaceService = mock(WorkspaceService.class);
        personMapper = mock(PersonMapper.class);
        sessionReadAudit = mock(AiAssistantSessionReadAudit.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of(
                Permission.ACTIVITY_CREATE,
                Permission.ACTIVITY_DELETE,
                Permission.TASK_CREATE,
                Permission.TASK_DELETE,
                Permission.NOTE_CREATE,
                Permission.NOTE_DELETE));
        when(workspaceService.getMembers(WORKSPACE_ID))
                .thenReturn(List.of(user(USER_ID, "Ada Owner", "ada-owner")));
        accessibleSession = new AiChatSession();
        accessibleSession.setId(SESSION_ID);
        accessibleSession.setCreatedByUserId(USER_ID);
        accessibleSession.setStatus("active");
        when(chatMapper.getAccessibleSessionById(
                WORKSPACE_ID, USER_ID, SESSION_ID)).thenReturn(accessibleSession);
        service = new AiAssistantToolCallReadService(
                new AiAssistantToolCatalog(),
                chatMapper,
                workspaceService,
                personMapper,
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                mock(PipelineMapper.class),
                sessionReadAudit,
                JsonMapper.builder().build(),
                CLOCK);
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
                        + "\"status\":\"available\","
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
