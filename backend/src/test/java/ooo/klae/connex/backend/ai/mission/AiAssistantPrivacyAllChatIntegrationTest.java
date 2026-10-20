package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAssistantProposalSet;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiAssistantProposalSetMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Exercises member boundaries while invalidated rows are deliberately still physically retained. */
class AiAssistantPrivacyAllChatIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAssistantService assistant;
    @MockitoSpyBean private ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher withdrawals;
    @Autowired private AiChatTurnPersistenceService turns;
    @Autowired private AiAssistantWriteToolService writes;
    @Autowired private AiAssistantToolCallReadService toolReads;
    @Autowired private AiAssistantProposalSetMapper proposalSets;
    @Autowired private TenantLifecycleMapper lifecycle;
    @Autowired private AiAssistantPrivacyService authority;
    @Autowired private AiRestrictionEpoch epochs;
    @Autowired private AiProperties properties;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper workRows;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.AiChatMapper lockedChats;

    @Test
    void neverTaintedSessionIsImmediatelyWithheld() {
        AiChatSession session = session();
        AiChatMessageCreateRequest message = new AiChatMessageCreateRequest();
        message.setContent("Uncited copied private text");
        assistant.appendMessage(session.getId(), message);
        assertEquals(1, assistant.page(0, 20).total());
        assertEquals(1, assistant.get(session.getId(), 0, 20).messages().total());

        invalidateV2();
        org.mockito.Mockito.verify(withdrawals).workspacePrivacyNow(workspace.getId(), 1L);

        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        assertEquals(0, assistant.page(0, 20).total());
        assertThrows(RuntimeException.class, () -> assistant.get(session.getId(), 0, 20));
        AiChatSession fresh = session();
        assertEquals(2, fresh.getOrganizationPrivacyGeneration());
        assertNotNull(assistant.get(fresh.getId(), 0, 20));
    }

    @Test
    void knownIdsCannotReadOrDecideOldContent() {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        var proposal = turns.proposeWriteTool(turn, 1,
                new AiAssistantPreparedWrite("add_tag", ToolTier.CONFIRM, "company", 1, "{}"));
        invalidateV2();
        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        assertThrows(RuntimeException.class, () -> toolReads.get(session.getId(), proposal.id()));
        assertThrows(RuntimeException.class, () -> writes.approve(session.getId(), proposal.id()));
        assertThrows(RuntimeException.class, () -> writes.reject(session.getId(), proposal.id()));
        assertThrows(RuntimeException.class, () -> turns.readTurn(session.getId(), turn.turnId()));
        assertEquals("proposed", jdbc.queryForObject(
                "SELECT status FROM ai_chat_tool_call WHERE workspace_id=? AND id=?",
                String.class, workspace.getId(), proposal.id()));
    }

    @Test
    void oldGenerationSessionCannotAcceptAnotherTurn() {
        AiChatSession session = session();
        invalidateV2();
        assertThrows(RuntimeException.class, () -> turns.queue(session.getId(),
                new AiChatTurnCreateRequest("New input", List.of()), epochs.current(workspace.getId())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id=?",
                Integer.class, workspace.getId()));
    }

    @Test
    void delayedSummaryAndProposalCannotAdoptCurrentAuthority() {
        AiChatSession session = session();
        AiChatQueuedTurn original = running(session);
        invalidateV2();
        assertThrows(RuntimeException.class, () -> turns.upsertHistorySummary(original, null, 0,
                "Delayed summary", "{\"kind\":\"history_summary\",\"throughSeq\":1}", 1, 1));
        assertThrows(RuntimeException.class, () -> turns.proposeWriteTool(original, 1,
                new AiAssistantPreparedWrite("add_tag", ToolTier.CONFIRM, "company", 1, "{}")));
        assertEquals(1, original.privacyToken().organizationPrivacyGeneration());
        assertEquals(2, authority.current(workspace.getOrgId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND author_kind='system'",
                Integer.class, workspace.getId()));
    }

    @Test
    void proposalChildrenRetainBothOriginGenerations() {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        AiChatToolCall child = child(parent);
        child.setPrivacyGeneration(999L);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(child)));
        var stored = proposalSets.listMembers(workspace.getId(), session.getId(), parent.getProposalKey(), 1L);
        assertEquals(1, stored.size());
        assertEquals(parent.getPrivacyGeneration(), stored.getFirst().getPrivacyGeneration());
        assertEquals(parent.getOrganizationPrivacyGeneration(), stored.getFirst().getOrganizationPrivacyGeneration());
        assertTrue(proposalSets.hasCompleteMembership(workspace.getId(), session.getId(), parent.getProposalKey(), 1L));
        invalidateV2();
        assertNull(proposalSets.findByKey(workspace.getId(), session.getId(), parent.getProposalKey(), 2L));
        assertTrue(proposalSets.listMembers(workspace.getId(), session.getId(), parent.getProposalKey(), 2L).isEmpty());
        assertFalse(proposalSets.hasCompleteMembership(workspace.getId(), session.getId(), parent.getProposalKey(), 2L));
    }

    @Test
    void inconsistentOriginCannotProjectOrExportProposalHoldings() {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(child(parent))));
        turns.resolve(turn, "Current answer", "{}", 0, 0);
        assertTrue(exportsAny("ai_assistant_proposal_set"));
        assertTrue(exportsAny("ai_chat_tool_call"));

        for (String table : List.of("ai_chat_turn", "ai_chat_message")) {
            int id = table.equals("ai_chat_turn") ? turn.turnId() : turn.userMessageId();
            jdbc.update("UPDATE " + table + " SET organization_privacy_generation=0 WHERE workspace_id=? AND id=?",
                    workspace.getId(), id);
            assertNull(proposalSets.findByKey(workspace.getId(), session.getId(), parent.getProposalKey(), 1L));
            assertNull(proposalSets.findByCallIdentity(workspace.getId(), session.getId(), turn.turnId(), 1, 0, 1L));
            assertTrue(proposalSets.listMembers(workspace.getId(), session.getId(), parent.getProposalKey(), 1L).isEmpty());
            assertFalse(proposalSets.hasCompleteMembership(workspace.getId(), session.getId(), parent.getProposalKey(), 1L));
            assertEquals(Integer.valueOf(0), transaction().execute(status -> proposalSets.insertMember(child(parent))));
            assertFalse(exportsAny("ai_assistant_proposal_set"));
            assertFalse(exportsAny("ai_chat_tool_call"));
            jdbc.update("UPDATE " + table + " SET organization_privacy_generation=1 WHERE workspace_id=? AND id=?",
                    workspace.getId(), id);
        }
        assertNotNull(proposalSets.findByKey(workspace.getId(), session.getId(), parent.getProposalKey(), 1L));
    }

    @Test
    void v2RetiresEntireSession() {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(child(parent))));
        turns.resolve(turn, "Original answer", "{}", 0, 0);
        String operation = invalidateV2();
        finish(operation);
        assertNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=?",
                Integer.class, workspace.getId()));
    }

    @Test
    void erasesProposalParentAnchoredToPreservedInput() {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(child(parent))));
        turns.resolve(turn, "Original answer", "{}", 0, 0);
        transaction().executeWithoutResult(status -> {
            chatMapper.getSessionByIdForMaintenanceUpdate(workspace.getId(), session.getId());
            assertEquals(1, chatMapper.taintSession(workspace.getId(), session.getId(),
                    turn.privacyToken().privacyGeneration(), turn.userMessageSeq() + 1));
        });

        String operation = invalidate();
        finish(operation);

        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), session.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND id=? AND author_kind='user'",
                Integer.class, workspace.getId(), turn.userMessageId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE workspace_id=?",
                Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=?",
                Integer.class, workspace.getId()));
    }

    @Test
    void orphanSetResidualPreventsCompletion() {
        String operation = invalidateV2();
        AiChatSession fresh = session();
        AiChatQueuedTurn turn = running(fresh);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        AiChatToolCall member = child(parent);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(member)));
        turns.resolve(turn, "Current answer", "{}", 0, 0);
        jdbc.update("UPDATE ai_chat_tool_call SET privacy_generation=999 WHERE workspace_id=? AND id=?",
                workspace.getId(), member.getId());

        for (int batch = 0; batch < 5; batch++) {
            assertFalse(privacyService.batch(workspace.getId(), operation));
        }
        assertNull(privacyMapper.getWork(workspace.getId(), operation).getCompletedAt());
        assertTrue(privacyMapper.allChatResidualCount(workspace.getId(), 1, operation) > 0);
        assertFalse(proposalSets.hasCompleteMembership(workspace.getId(), fresh.getId(), parent.getProposalKey(), 2L));

        jdbc.update("UPDATE ai_chat_tool_call SET privacy_generation=? WHERE workspace_id=? AND id=?",
                parent.getPrivacyGeneration(), workspace.getId(), member.getId());
        finish(operation);
        assertNotNull(chatMapper.getPrivacySession(workspace.getId(), fresh.getId()));
    }

    @Test
    void proposalParentLocksPrecedeChildrenAndTurns() throws Exception {
        AiChatSession session = session();
        AiChatQueuedTurn turn = running(session);
        AiAssistantProposalSet parent = proposalSet(turn);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insert(parent)));
        AiChatToolCall member = child(parent);
        assertEquals(Integer.valueOf(1), transaction().execute(status -> proposalSets.insertMember(member)));
        turns.resolve(turn, "Original answer", "{}", 0, 0);
        String operation = invalidateV2();
        for (int batch = 0; batch < 10; batch++) {
            var work = privacyMapper.getWork(workspace.getId(), operation);
            if ("sessions".equals(work.getPhase()) && "tools".equals(work.getSessionPhase())) {
                break;
            }
            assertFalse(privacyService.batch(workspace.getId(), operation));
        }
        assertEquals("tools", privacyMapper.getWork(workspace.getId(), operation).getSessionPhase());
        CountDownLatch parentHeld = new CountDownLatch(1);
        CountDownLatch releaseParent = new CountDownLatch(1);
        CountDownLatch requestingParent = new CountDownLatch(1);
        AtomicLong connection = new AtomicLong();
        AtomicBoolean parentAcquired = new AtomicBoolean();
        AtomicBoolean childAcquired = new AtomicBoolean();
        AtomicBoolean turnAcquired = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean cleanup = Thread.currentThread().getName().equals("assistant-parent-purge");
            if (cleanup) {
                connection.set(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                requestingParent.countDown();
            }
            Object result = invocation.callRealMethod();
            if (cleanup) {
                parentAcquired.set(true);
            }
            return result;
        }).when(workRows).lockProposalParent(workspace.getId(), session.getId(), parent.getProposalKey());
        org.mockito.Mockito.doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-parent-purge")) {
                assertTrue(parentAcquired.get());
                childAcquired.set(true);
            }
            return invocation.callRealMethod();
        }).when(lockedChats).getToolCallBySessionForUpdate(workspace.getId(), session.getId(), member.getId());
        org.mockito.Mockito.doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("assistant-parent-purge")) {
                assertTrue(parentAcquired.get());
                assertTrue(childAcquired.get());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_proposal_set WHERE workspace_id=? AND session_id=?",
                        Integer.class, workspace.getId(), session.getId()));
                turnAcquired.set(true);
            }
            return invocation.callRealMethod();
        }).when(lockedChats).getTurnByIdForUpdate(workspace.getId(), session.getId(), turn.turnId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> {
                authenticateAs(currentUser, workspace.getId());
                try {
                    transaction().executeWithoutResult(status -> {
                        workRows.lockProposalParent(workspace.getId(), session.getId(), parent.getProposalKey());
                        parentHeld.countDown();
                        await(releaseParent);
                    });
                } finally {
                    clearAuthentication();
                }
            });
            assertTrue(parentHeld.await(10, TimeUnit.SECONDS));
            var cleanup = workers.submit(() -> {
                Thread.currentThread().setName("assistant-parent-purge");
                authenticateAs(currentUser, workspace.getId());
                try {
                    finish(operation);
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(requestingParent.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "ai_assistant_proposal_set",
                        workspace.getId() + ", '" + parent.getProposalKey() + "'");
                assertFalse(childAcquired.get());
                assertFalse(turnAcquired.get());
            } finally {
                releaseParent.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            cleanup.get(10, TimeUnit.SECONDS);
        } finally {
            releaseParent.countDown();
        }
        assertTrue(parentAcquired.get());
        assertTrue(childAcquired.get());
        assertTrue(turnAcquired.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private boolean exportsAny(String table) {
        return Boolean.TRUE.equals(transaction().execute(status -> {
            try (var cursor = lifecycle.streamRows(workspace.getId(), TenantLifecycleRegistry.require(table), 1L)) {
                return cursor.iterator().hasNext();
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }));
    }

    private AiChatSession session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(authority.current(workspace.getOrgId()));
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Private user-set title");
        session.setTitleUserSet(true);
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private AiChatQueuedTurn running(AiChatSession session) {
        AiChatQueuedTurn turn = turns.queue(session.getId(),
                new AiChatTurnCreateRequest("Original private input", List.of()), epochs.current(workspace.getId()));
        turns.markRunning(turn, new AiRunLeaseGuard(properties.getRunLeaseTtl()));
        return turn;
    }

    private String invalidateV2() {
        UUID key = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateAssistantOrganization(
                workspace.getOrgId(), List.of(workspace.getId()), key, "restriction", null, null));
        privacyService.reconcileAssistantOperations(workspace.getId());
        return key.toString();
    }

    private AiAssistantProposalSet proposalSet(AiChatQueuedTurn turn) {
        AiAssistantProposalSet parent = new AiAssistantProposalSet();
        parent.setWorkspaceId(workspace.getId());
        parent.setSessionId(turn.sessionId());
        parent.setOrganizationPrivacyGeneration(turn.privacyToken().organizationPrivacyGeneration());
        parent.setPrivacyGeneration(turn.privacyToken().privacyGeneration());
        parent.setProposalKey(UUID.randomUUID().toString());
        parent.setOriginTurnId(turn.turnId());
        parent.setMessageId(turn.userMessageId());
        parent.setRequestedByUserId(turn.userId());
        parent.setStepNumber(1);
        parent.setCallOrdinal(0);
        parent.setToolName("add_tag");
        parent.setInvocationMode("bulk");
        parent.setRowCount(1);
        parent.setIntentFingerprint("a".repeat(64));
        parent.setScopeRevision("1");
        return parent;
    }

    private AiChatToolCall child(AiAssistantProposalSet parent) {
        AiChatToolCall child = new AiChatToolCall();
        child.setWorkspaceId(parent.getWorkspaceId());
        child.setSessionId(parent.getSessionId());
        child.setOrganizationPrivacyGeneration(parent.getOrganizationPrivacyGeneration());
        child.setMessageId(parent.getMessageId());
        child.setRequestedByUserId(parent.getRequestedByUserId());
        child.setToolName(parent.getToolName());
        child.setProposalKey(parent.getProposalKey());
        child.setProposalOrdinal(1);
        child.setProposalPreparedAt("2000-01-01 00:00:00.123456");
        child.setServerProposal(true);
        child.setIdempotencyKey(UUID.randomUUID().toString());
        child.setArgumentsJson("{}");
        return child;
    }
}
