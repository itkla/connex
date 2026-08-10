package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnDto;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;

class AiChatTurnLazyExpiryTest extends AbstractServiceTest {
    private static final String STALE_TIMESTAMP = "2000-01-01 00:00:00.000000";

    @Autowired private AiAssistantTurnService turnService;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AiFeatureGate featureGate;
    @MockitoBean private AiGenerationService generationService;

    @Test
    void staleRunningTurnDoesNotPermanentlyBlockTheNextTurn() {
        AiChatSession session = session(workspace, currentUser);
        AiChatTurn stale = turn(session, currentUser, "running");
        makeStale(stale);
        when(generationService.startAtRestrictionEpoch(
                eq(AiFeature.ASSISTANT_CHAT), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(acceptedStatus());

        AiChatTurnAcceptedDto accepted = turnService.start(
                session.getId(), new AiChatTurnCreateRequest("Continue", List.of()));

        AiChatTurn expired = stored(session, stale);
        AiChatTurn replacement = Objects.requireNonNull(chatMapper.getTurnById(
                workspace.getId(), session.getId(), accepted.turnId()));
        assertNotEquals(stale.getId(), accepted.turnId());
        assertEquals("timed_out", expired.getStatus());
        assertEquals("generation_timeout", expired.getTerminalReason());
        assertEquals("queued", replacement.getStatus());
        assertEquals(1, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
    }

    @Test
    void readingAStaleActiveTurnReturnsTheStableTimedOutState() {
        AiChatSession session = session(workspace, currentUser);
        AiChatTurn stale = turn(session, currentUser, "running");
        makeStale(stale);

        AiChatTurnDto result = turnService.get(session.getId(), stale.getId());

        assertEquals("timed_out", result.status());
        assertEquals("generation_timeout", result.terminalReason());
    }

    @Test
    void queuedToRunningRefreshDoesNotExtendTheCreationBasedDeadline() {
        AiChatSession session = session(workspace, currentUser);
        AiChatTurn stale = turn(session, currentUser, "queued");
        makeStale(stale);

        assertEquals(1, chatMapper.markTurnRunning(
                workspace.getId(), session.getId(), stale.getId()));
        assertNotEquals(STALE_TIMESTAMP, stored(session, stale).getUpdatedAt());

        AiChatTurnDto result = turnService.get(session.getId(), stale.getId());

        assertEquals("timed_out", result.status());
        assertEquals("generation_timeout", result.terminalReason());
    }

    @Test
    void expiryIsIdempotentAndDoesNotOverwriteTerminalState() {
        AiChatSession session = session(workspace, currentUser);
        AiChatTurn stale = turn(session, currentUser, "queued");
        makeStale(stale);
        AiChatTurnDto first = turnService.get(session.getId(), stale.getId());
        AiChatTurnDto second = turnService.get(session.getId(), stale.getId());

        AiChatTurn failed = turn(session, currentUser, "queued");
        assertEquals(1, chatMapper.updateTurnTerminal(
                workspace.getId(), session.getId(), failed.getId(),
                "failed", "provider_error", "queued", null));
        makeStale(failed);
        AiChatTurnDto terminal = turnService.get(session.getId(), failed.getId());

        assertEquals(first, second);
        assertEquals("timed_out", second.status());
        assertEquals("generation_timeout", second.terminalReason());
        assertEquals("failed", terminal.status());
        assertEquals("provider_error", terminal.terminalReason());
    }

    @Test
    void turnInsideTheGenerationLifetimeIsNotExpired() {
        AiChatSession session = session(workspace, currentUser);
        AiChatTurn running = turn(session, currentUser, "running");

        AiChatTurnDto result = turnService.get(session.getId(), running.getId());

        assertEquals("running", result.status());
        assertNull(result.terminalReason());
    }

    @Test
    void expiryOnlyTouchesTheRequestedWorkspace() {
        AiChatSession localSession = session(workspace, currentUser);
        AiChatTurn local = turn(localSession, currentUser, "running");
        makeStale(local);

        Workspace foreignWorkspace = new Workspace();
        foreignWorkspace.setName("Foreign " + unique());
        foreignWorkspace.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreignWorkspace);
        User foreignOwner = newUser();
        workspaceMapper.addMember(foreignWorkspace.getId(), foreignOwner.getId(), "owner");
        AiChatSession foreignSession = session(foreignWorkspace, foreignOwner);
        AiChatTurn foreign = turn(foreignSession, foreignOwner, "running");
        makeStale(foreign);

        turnService.get(localSession.getId(), local.getId());

        assertEquals("timed_out", stored(localSession, local).getStatus());
        assertEquals("running", stored(foreignSession, foreign).getStatus());
        assertNull(stored(foreignSession, foreign).getTerminalReason());
    }

    private AiChatSession session(Workspace owningWorkspace, User owner) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(owningWorkspace.getId());
        session.setCreatedByUserId(owner.getId());
        session.setTitle("Turn " + unique());
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private AiChatTurn turn(AiChatSession session, User requester, String status) {
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(session.getWorkspaceId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(requester.getId());
        turn.setStatus(status);
        chatMapper.insertTurn(turn);
        return turn;
    }

    private void makeStale(AiChatTurn turn) {
        jdbcTemplate.update(
                "UPDATE ai_chat_turn SET created_at = ?, updated_at = ? "
                        + "WHERE workspace_id = ? AND id = ?",
                STALE_TIMESTAMP, STALE_TIMESTAMP, turn.getWorkspaceId(), turn.getId());
    }

    private AiChatTurn stored(AiChatSession session, AiChatTurn turn) {
        return Objects.requireNonNull(chatMapper.getTurnById(
                session.getWorkspaceId(), session.getId(), turn.getId()));
    }

    private static AiGenerationStatusDto acceptedStatus() {
        return new AiGenerationStatusDto(
                "turn-handle",
                AiFeature.ASSISTANT_CHAT.wireKey(),
                "accepted",
                null,
                null,
                2_000,
                120_000,
                "2026-08-09T12:02:00Z");
    }
}
