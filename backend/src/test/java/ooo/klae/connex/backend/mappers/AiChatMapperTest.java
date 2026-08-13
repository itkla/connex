package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import tools.jackson.databind.json.JsonMapper;

class AiChatMapperTest extends AbstractMapperTest {

    @Autowired private AiChatMapper chatMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void sessionAndMessageColumnsRoundTrip() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Quarterly planning", "private");
        AiChatMessage message = message(session, owner, 1, "A durable message");

        AiChatSession foundSession = chatMapper.getSessionById(
            workspace.getId(), owner.getId(), session.getId());
        AiChatMessage foundMessage = chatMapper.getMessageById(
            workspace.getId(), session.getId(), message.getId());

        assertNotEquals(0, session.getId());
        assertNotNull(foundSession);
        assertEquals(owner.getId(), foundSession.getCreatedByUserId());
        assertEquals("Quarterly planning", foundSession.getTitle());
        assertTrue(foundSession.isTitleUserSet());
        assertEquals("private", foundSession.getVisibility());
        assertEquals("active", foundSession.getStatus());
        assertTrue(foundSession.isOwnedByCurrentUser());
        assertNotNull(foundSession.getLastMessageAt());
        assertNotNull(foundSession.getCreatedAt());
        assertNotNull(foundMessage);
        assertEquals(1, foundMessage.getSeq());
        assertEquals("user", foundMessage.getAuthorKind());
        assertEquals(owner.getId(), foundMessage.getAuthorUserId());
        assertEquals("A durable message", foundMessage.getContent());
        assertNotNull(foundMessage.getCreatedAt());
    }

    @Test
    void identicalLogicalDataRemainsWorkspaceIsolated() {
        User owner = newUser();
        Workspace other = newWorkspace();
        AiChatSession first = session(workspace, owner, "Same title", "shared");
        AiChatSession second = session(other, owner, "Same title", "shared");
        message(first, owner, 1, "Same content");
        message(second, owner, 1, "Same content");

        assertNotNull(chatMapper.getAccessibleSessionById(
            workspace.getId(), owner.getId(), first.getId()));
        assertNull(chatMapper.getAccessibleSessionById(
            workspace.getId(), owner.getId(), second.getId()));
        assertEquals(1, chatMapper.countMessages(workspace.getId(), first.getId()));
        assertEquals(0, chatMapper.countMessages(workspace.getId(), second.getId()));
    }

    @Test
    void ownerAndParticipantUnionDeduplicatesAndOrdersByLastMessageThenId() {
        User owner = newUser();
        User participant = newUser();
        AiChatSession oldest = session(workspace, owner, "Oldest", "shared");
        AiChatSession tiedLowerId = session(workspace, owner, "Tied lower", "shared");
        AiChatSession tiedHigherId = session(workspace, owner, "Tied higher", "shared");
        chatMapper.insertParticipant(workspace.getId(), oldest.getId(), owner.getId());
        chatMapper.insertParticipant(workspace.getId(), oldest.getId(), participant.getId());
        chatMapper.insertParticipant(workspace.getId(), tiedLowerId.getId(), participant.getId());
        chatMapper.insertParticipant(workspace.getId(), tiedHigherId.getId(), participant.getId());
        jdbcTemplate.update(
            "UPDATE ai_chat_session SET last_message_at = ? WHERE workspace_id = ? AND id = ?",
            "2026-08-01 00:00:00.000000", workspace.getId(), oldest.getId());
        jdbcTemplate.update(
            "UPDATE ai_chat_session SET last_message_at = ? WHERE workspace_id = ? AND id IN (?, ?)",
            "2026-08-02 00:00:00.000000", workspace.getId(), tiedLowerId.getId(), tiedHigherId.getId());

        List<AiChatSession> ownerRows = chatMapper.listAccessibleSessions(
            workspace.getId(), owner.getId(), 100, 0);
        List<AiChatSession> participantRows = chatMapper.listAccessibleSessions(
            workspace.getId(), participant.getId(), 100, 0);

        assertEquals(3, ownerRows.size());
        assertEquals(3, chatMapper.countAccessibleSessions(workspace.getId(), owner.getId()));
        assertEquals(
            List.of(tiedHigherId.getId(), tiedLowerId.getId(), oldest.getId()),
            participantRows.stream().map(AiChatSession::getId).toList());
        assertFalse(participantRows.getFirst().isOwnedByCurrentUser());
    }

    @Test
    void invitationsDoNotGrantAccessOrFanoutUntilJoinAndPrivateVisibilityRevokesFanout() {
        User owner = newUser();
        User participant = newUser();
        AiChatSession session = session(workspace, owner, "Invitation state", "shared");
        chatMapper.insertInvitation(
                workspace.getId(), session.getId(), participant.getId(), owner.getId());

        assertEquals(0, chatMapper.countAccessibleSessions(
                workspace.getId(), participant.getId()));
        assertEquals(1, chatMapper.countInvitedSessions(
                workspace.getId(), participant.getId()));
        assertEquals(List.of(owner.getId()), chatMapper.listRealtimeRecipientUserIds(
                workspace.getId(), session.getId()));

        assertEquals(1, chatMapper.joinParticipant(
                workspace.getId(), session.getId(), participant.getId()));
        assertEquals(1, chatMapper.countAccessibleSessions(
                workspace.getId(), participant.getId()));
        assertEquals(List.of(owner.getId(), participant.getId()).stream().sorted().toList(),
                chatMapper.listRealtimeRecipientUserIds(workspace.getId(), session.getId()));

        chatMapper.updateSession(
                workspace.getId(), session.getId(), null, null, "private");
        assertEquals(List.of(owner.getId()), chatMapper.listRealtimeRecipientUserIds(
                workspace.getId(), session.getId()));
    }

    @Test
    void generatedTitleCannotReplaceATitleAfterManualRename() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "New conversation", "private");
        session.setTitleUserSet(false);
        jdbcTemplate.update(
                "UPDATE ai_chat_session SET title_user_set = FALSE WHERE workspace_id = ? AND id = ?",
                workspace.getId(), session.getId());
        AiChatMessage answer = new AiChatMessage();
        answer.setWorkspaceId(workspace.getId());
        answer.setSessionId(session.getId());
        answer.setSeq(1);
        answer.setAuthorKind("assistant");
        answer.setContent("Resolved answer");
        chatMapper.insertMessage(answer);

        assertEquals(1, chatMapper.updateGeneratedTitle(
                workspace.getId(), session.getId(), "Pipeline review"));
        assertEquals(1, chatMapper.updateSession(
                workspace.getId(), session.getId(), "My renewal notes", null, null));
        assertEquals(0, chatMapper.updateGeneratedTitle(
                workspace.getId(), session.getId(), "Overwritten title"));

        AiChatSession stored = chatMapper.getSessionById(
                workspace.getId(), owner.getId(), session.getId());
        assertNotNull(stored);
        assertEquals("My renewal notes", stored.getTitle());
        assertTrue(stored.isTitleUserSet());
    }

    @Test
    void retainedSessionsDeriveFromCurrentActiveMembershipAndRemainWorkspaceScoped() {
        User admin = newUser();
        User activeAuthor = newUser();
        User departedAuthor = newUser();
        AiChatSession active = session(workspace, activeAuthor, "Active author", "private");
        AiChatSession departed = session(workspace, departedAuthor, "Departed author", "private");
        AiChatSession erased = session(workspace, activeAuthor, "Erased author", "private");
        Workspace other = newWorkspace();
        AiChatSession otherWorkspace = session(other, departedAuthor, "Other workspace", "private");
        workspaceMapper.removeMember(workspace.getId(), departedAuthor.getId());
        jdbcTemplate.update(
            "UPDATE ai_chat_session SET created_by_user_id = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), erased.getId());

        List<Integer> activeMemberIds = List.of(admin.getId(), activeAuthor.getId());
        List<AiChatSession> retained = chatMapper.listRetainedSessions(
            workspace.getId(), admin.getId(), activeMemberIds, 100, 0);

        assertEquals(
            List.of(departed.getId(), erased.getId()),
            retained.stream().map(AiChatSession::getId).sorted().toList());
        assertEquals(2, chatMapper.countRetainedSessions(workspace.getId(), activeMemberIds));
        assertNull(chatMapper.getRetainedSessionById(
            workspace.getId(), admin.getId(), active.getId(), activeMemberIds));
        assertNotNull(chatMapper.getRetainedSessionById(
            workspace.getId(), admin.getId(), departed.getId(), activeMemberIds));
        assertNull(chatMapper.getRetainedSessionById(
            workspace.getId(), admin.getId(), otherWorkspace.getId(), activeMemberIds));

        workspaceMapper.addMember(workspace.getId(), departedAuthor.getId(), "member");
        List<Integer> rejoinedMemberIds = List.of(
            admin.getId(), activeAuthor.getId(), departedAuthor.getId());

        assertNull(chatMapper.getRetainedSessionById(
            workspace.getId(), admin.getId(), departed.getId(), rejoinedMemberIds));
        assertEquals(1, chatMapper.countRetainedSessions(workspace.getId(), rejoinedMemberIds));
    }

    @Test
    void messageReplayIsAscendingAndWorkspaceScoped() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Replay", "private");
        message(session, owner, 2, "second");
        message(session, owner, 1, "first");
        message(session, owner, 3, "third");

        List<AiChatMessage> replay = chatMapper.listMessages(
            workspace.getId(), session.getId(), 100, 0);

        assertEquals(List.of(1, 2, 3), replay.stream().map(AiChatMessage::getSeq).toList());
        assertEquals(3, chatMapper.countMessages(workspace.getId(), session.getId()));
        assertTrue(chatMapper.listMessages(workspace.getId() + 1, session.getId(), 100, 0).isEmpty());
    }

    @Test
    void recentReplayStopsAtQueuedSequenceWithoutCountingTheSummaryRow() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Bounded replay", "shared");
        message(session, owner, 1, "before");
        AiChatMessage summary = new AiChatMessage();
        summary.setWorkspaceId(workspace.getId());
        summary.setSessionId(session.getId());
        summary.setSeq(2);
        summary.setAuthorKind("system");
        summary.setContent("durable summary");
        summary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":1,\"resources\":[]}");
        chatMapper.insertMessage(summary);
        message(session, owner, 3, "queued request");
        message(session, owner, 4, "later participant message");

        List<AiChatMessage> replay = chatMapper.listRecentMessages(
                workspace.getId(), session.getId(), 3, 50);
        List<AiChatMessage> latestAtBoundary = chatMapper.listRecentMessages(
                workspace.getId(), session.getId(), 3, 1);

        assertEquals(List.of(1, 3), replay.stream().map(AiChatMessage::getSeq).toList());
        assertEquals(List.of(3), latestAtBoundary.stream().map(AiChatMessage::getSeq).toList());
    }

    @Test
    void durableTurnToolAndStructuredMessageStateRoundTripsWithinWorkspace() throws Exception {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Agent loop", "private");
        AiChatMessage userMessage = message(session, owner, 1, "Investigate");
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(owner.getId());
        turn.setStatus("queued");
        chatMapper.insertTurn(turn);
        assertEquals(1, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
        assertEquals(1, chatMapper.markTurnRunning(
                workspace.getId(), session.getId(), turn.getId()));

        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(workspace.getId());
        toolCall.setMessageId(userMessage.getId());
        toolCall.setToolName("get_record");
        toolCall.setStatus("proposed");
        toolCall.setArgumentsJson("{\"handle\":\"r1\"}");
        String thoughtSignature = "opaque /+==\n\u65e5\u672c\u8a9e";
        toolCall.setThoughtSignature(thoughtSignature);
        toolCall.setIdempotencyKey("turn-" + turn.getId() + "-step-1");
        chatMapper.insertToolCall(toolCall);
        assertEquals(
                toolCall.getId(),
                chatMapper.getToolCallBySession(
                        workspace.getId(), session.getId(), toolCall.getId()).getId());
        assertEquals(
                List.of(toolCall.getId()),
                chatMapper.listPendingToolCallsBySession(
                        workspace.getId(), session.getId()).stream()
                        .map(AiChatToolCall::getId)
                        .toList());
        assertNull(chatMapper.getToolCallBySession(
                workspace.getId() + 1, session.getId(), toolCall.getId()));
        assertEquals(1, chatMapper.updateToolCall(
                workspace.getId(), userMessage.getId(), toolCall.getId(),
                "executed", "{\"kind\":\"person\"}", owner.getId()));
        AiChatToolCall storedTool = chatMapper.getToolCallById(
                workspace.getId(), userMessage.getId(), toolCall.getId());
        AiChatToolCall replayedTool = chatMapper.getToolCallByIdempotencyKey(
                workspace.getId(), toolCall.getIdempotencyKey());
        AiChatToolCall lockedTool = chatMapper.getToolCallBySessionForUpdate(
                workspace.getId(), session.getId(), toolCall.getId());
        assertEquals("executed", storedTool.getStatus());
        assertEquals(owner.getId(), storedTool.getExecutedByUserId());
        assertEquals("turn-" + turn.getId() + "-step-1", storedTool.getIdempotencyKey());
        assertEquals(thoughtSignature, storedTool.getThoughtSignature());
        assertEquals(session.getId(), replayedTool.getSessionId());
        assertEquals(owner.getId(), replayedTool.getRequestedByUserId());
        assertEquals(thoughtSignature, replayedTool.getThoughtSignature());
        assertEquals(toolCall.getId(), lockedTool.getId());
        assertEquals(thoughtSignature, lockedTool.getThoughtSignature());
        assertTrue(chatMapper.listPendingToolCallsBySession(
                workspace.getId(), session.getId()).isEmpty());
        AiChatToolCall secondToolCall = new AiChatToolCall();
        secondToolCall.setWorkspaceId(workspace.getId());
        secondToolCall.setMessageId(userMessage.getId());
        secondToolCall.setToolName("get_record");
        secondToolCall.setStatus("executed");
        secondToolCall.setArgumentsJson("{\"handle\":\"r2\"}");
        secondToolCall.setIdempotencyKey("turn-" + turn.getId() + "-step-2");
        chatMapper.insertToolCall(secondToolCall);
        assertNull(chatMapper.getToolCallById(
                workspace.getId(), userMessage.getId(), secondToolCall.getId())
                .getThoughtSignature());
        assertEquals(
                List.of(toolCall.getId()),
                chatMapper.listToolCallsBySession(
                                workspace.getId(), session.getId(), false, 1).stream()
                        .map(AiChatToolCall::getId)
                        .toList());
        assertTrue(chatMapper.listToolCallsBySession(
                workspace.getId(), session.getId(), true, 1).isEmpty());
        assertTrue(chatMapper.listToolCallsBySession(
                workspace.getId() + 1, session.getId(), false, 1).isEmpty());

        AiChatMessage answer = new AiChatMessage();
        answer.setWorkspaceId(workspace.getId());
        answer.setSessionId(session.getId());
        answer.setSeq(2);
        answer.setAuthorKind("assistant");
        answer.setContent("Resolved");
        answer.setStructuredJson("{\"turnId\":" + turn.getId() + ",\"citations\":[]}");
        answer.setInputTokens(21);
        answer.setOutputTokens(8);
        chatMapper.insertMessage(answer);
        AiChatMessage unrelatedAnswer = new AiChatMessage();
        unrelatedAnswer.setWorkspaceId(workspace.getId());
        unrelatedAnswer.setSessionId(session.getId());
        unrelatedAnswer.setSeq(3);
        unrelatedAnswer.setAuthorKind("assistant");
        unrelatedAnswer.setContent("Unrelated");
        unrelatedAnswer.setStructuredJson("{\"turnId\":999,\"citations\":[]}");
        chatMapper.insertMessage(unrelatedAnswer);
        List<AiChatMessage> assistantMessages =
                chatMapper.listAssistantMessagesBySessionAndTurnIds(
                        workspace.getId(), session.getId(), List.of(turn.getId()), 100);
        assertEquals(List.of(answer.getId()), assistantMessages.stream()
                .map(AiChatMessage::getId)
                .toList());
        assertEquals(
                JsonMapper.builder().build().readTree(
                        "{\"turnId\":" + turn.getId() + ",\"citations\":[]}"),
                JsonMapper.builder().build().readTree(
                        assistantMessages.getFirst().getStructuredJson()));
        assertTrue(chatMapper.listAssistantMessagesBySessionAndTurnIds(
                workspace.getId() + 1, session.getId(), List.of(turn.getId()), 100).isEmpty());
        assertEquals(1, chatMapper.updateTurnTerminal(
                workspace.getId(), session.getId(), turn.getId(),
                "failed", "quota_exhausted", "running", null));

        AiChatMessage storedAnswer = chatMapper.getMessageById(
                workspace.getId(), session.getId(), answer.getId());
        AiChatTurn storedTurn = chatMapper.getTurnById(
                workspace.getId(), session.getId(), turn.getId());
        assertEquals(
                JsonMapper.builder().build().readTree(
                        "{\"turnId\":" + turn.getId() + ",\"citations\":[]}"),
                JsonMapper.builder().build().readTree(storedAnswer.getStructuredJson()));
        assertEquals(21, storedAnswer.getInputTokens());
        assertEquals(8, storedAnswer.getOutputTokens());
        assertEquals("failed", storedTurn.getStatus());
        assertEquals("quota_exhausted", storedTurn.getTerminalReason());
        assertEquals(0, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
        assertNull(chatMapper.getTurnById(
                workspace.getId() + 1, session.getId(), turn.getId()));
    }

    @Test
    void terminalCutoffUsesTheLatestTurnStateTransition() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Turn deadline", "private");
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(owner.getId());
        turn.setStatus("queued");
        chatMapper.insertTurn(turn);
        assertEquals(1, chatMapper.markTurnRunning(
                workspace.getId(), session.getId(), turn.getId()));
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 12, 0, 0);
        jdbcTemplate.update(
                "UPDATE ai_chat_turn SET created_at = ?, updated_at = ? "
                        + "WHERE workspace_id = ? AND id = ?",
                cutoff.minusMinutes(5), cutoff.plusSeconds(1),
                workspace.getId(), turn.getId());

        assertEquals(0, chatMapper.updateTurnTerminal(
                workspace.getId(), session.getId(), turn.getId(),
                "timed_out", "generation_timeout", "running", cutoff));
        jdbcTemplate.update(
                "UPDATE ai_chat_turn SET updated_at = ? WHERE workspace_id = ? AND id = ?",
                cutoff, workspace.getId(), turn.getId());
        assertEquals(1, chatMapper.updateTurnTerminal(
                workspace.getId(), session.getId(), turn.getId(),
                "timed_out", "generation_timeout", "running", cutoff));
        assertEquals(0, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
    }

    @Test
    void streamedPartialResetIsOffsetGuardedAndRestartsAtZero() {
        User owner = newUser();
        AiChatSession session = session(workspace, owner, "Stream reset", "private");
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(owner.getId());
        turn.setStatus("queued");
        turn.setPrivacyMode("unmasked");
        turn.setStreamed(true);
        chatMapper.insertTurn(turn);
        assertEquals(1, chatMapper.markTurnRunning(
                workspace.getId(), session.getId(), turn.getId()));
        assertEquals(1, chatMapper.appendTurnPartialContent(
                workspace.getId(), session.getId(), turn.getId(), 0, "A😀", 3));

        assertEquals(0, chatMapper.resetTurnPartialContent(
                workspace.getId(), session.getId(), turn.getId(), 2));
        assertEquals(1, chatMapper.resetTurnPartialContent(
                workspace.getId(), session.getId(), turn.getId(), 3));

        AiChatTurn reset = chatMapper.getTurnById(
                workspace.getId(), session.getId(), turn.getId());
        assertNull(reset.getPartialContent());
        assertEquals(0, reset.getPartialContentUtf16Offset());
        assertEquals(1, chatMapper.appendTurnPartialContent(
                workspace.getId(), session.getId(), turn.getId(), 0, "Repaired", 8));
        AiChatTurn repaired = chatMapper.getTurnById(
                workspace.getId(), session.getId(), turn.getId());
        assertEquals("Repaired", repaired.getPartialContent());
        assertEquals(8, repaired.getPartialContentUtf16Offset());
    }

    @Test
    void compositeForeignKeysRejectMismatchedWorkspaceParents() {
        User owner = newUser();
        Workspace other = newWorkspace();
        AiChatSession elsewhere = session(other, owner, "Elsewhere", "shared");

        assertThrows(DataIntegrityViolationException.class,
            () -> chatMapper.insertParticipant(
                workspace.getId(), elsewhere.getId(), owner.getId()));

        AiChatMessage mismatched = new AiChatMessage();
        mismatched.setWorkspaceId(workspace.getId());
        mismatched.setSessionId(elsewhere.getId());
        mismatched.setSeq(1);
        mismatched.setAuthorKind("user");
        mismatched.setAuthorUserId(owner.getId());
        mismatched.setContent("Rejected");
        assertThrows(DataIntegrityViolationException.class,
            () -> chatMapper.insertMessage(mismatched));
    }

    private AiChatSession session(
            Workspace targetWorkspace, User owner, String title, String visibility) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(targetWorkspace.getId());
        session.setCreatedByUserId(owner.getId());
        session.setTitle(title);
        session.setVisibility(visibility);
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private AiChatMessage message(
            AiChatSession session, User author, int sequence, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(session.getWorkspaceId());
        message.setSessionId(session.getId());
        message.setSeq(sequence);
        message.setAuthorKind("user");
        message.setAuthorUserId(author.getId());
        message.setContent(content);
        chatMapper.insertMessage(message);
        return message;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("AI chat " + unique());
        created.setSlug("ai-chat-" + unique());
        workspaceMapper.insert(created);
        return created;
    }
}
