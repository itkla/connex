package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.dto.AiAgentRunControlRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class AiAgentProposalIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    @Autowired private AiAssistantToolCallReadService legacyReads;
    @Autowired private AiAssistantWriteToolService legacyWrites;

    @Test
    void stagedRowsCannotBeReadOrApprovedByKnownId() {
        var fixture = staged("assign_owner");
        var action = fixture.selected().getFirst();
        var call = chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), action.getId());
        assertThrows(ConflictException.class, () -> reads.item(fixture.runId(), action.getActionSlot()));
        assertThrows(ConflictException.class, () -> decisions.decide(fixture.runId(), request(fixture, "approve", action)));
        assertThrows(ResourceNotFoundException.class, () -> legacyReads.get(fixture.sessionId(), call.getId()));
        assertThrows(ConflictException.class, () -> legacyWrites.approve(fixture.sessionId(), call.getId()));
        assertThrows(ConflictException.class, () -> legacyWrites.reject(fixture.sessionId(), call.getId()));
        assertPending(fixture);
        assertNull(companyMapper.getCompanyById(workspace.getId(), primary.getId()).getOwnerId());
    }

    @Test
    void reportAndExactFrozenMembershipCommitTogether() {
        var fixture = frozen("assign_owner", "update_record_fields");
        var output = reads.output(fixture.runId());
        assertNotNull(output.report());
        assertEquals(2, output.proposalSet().items().size());
        assertEquals(List.of(1, 2), output.proposalSet().items().stream().map(item -> item.ordinal()).toList());
        assertEquals(2, output.proposalSet().derivedCounts().pending());
        assertEquals("waiting_approval", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertNull(runs.getRun(workspace.getId(), fixture.runId()).getFinishedAt());
        assertNull(runs.getRun(workspace.getId(), fixture.runId()).getActiveSegmentId());
        assertNotNull(output.report().generatedAt());
    }

    @Test
    void failedFinalPublicationLeavesNoActionableItems() {
        var fixture = staged("assign_owner");
        jdbc.update("UPDATE company SET industry='Changed after preparation' WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId());
        assertThrows(RuntimeException.class, () -> freeze(fixture));
        assertNull(runs.getRun(workspace.getId(), fixture.runId()).getReportMessageId());
        var live = runs.getRun(workspace.getId(), fixture.runId());
        persistence.interrupt(live, "source_changed");
        assertEquals("failed", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
    }

    @Test
    void outputMembershipSurvivesTranscriptTruncation() {
        var fixture = frozen("assign_owner");
        for (int index = 0; index < 110; index++) {
            jdbc.update("INSERT INTO ai_chat_tool_call (workspace_id,message_id,tool_name,status,arguments_json,idempotency_key) VALUES (?,?,'get_record','executed','{}',?)",
                    workspace.getId(), fixture.policy().turn().userMessageId(), "history-" + fixture.runId() + "-" + index);
        }
        assertEquals(1, reads.output(fixture.runId()).proposalSet().items().size());
        assertEquals(fixture.selected().getFirst().getActionSlot(),
                reads.item(fixture.runId(), fixture.selected().getFirst().getActionSlot()).actionSlot());
    }

    @Test
    void everyMissionSingletonLeavesV233ColumnsNull() {
        var fixture = frozen("assign_owner", "update_record_fields");
        for (var action : fixture.selected()) {
            var row = jdbc.queryForMap("SELECT proposal_origin,proposal_key,proposal_ordinal,proposal_prepared_at FROM ai_chat_tool_call WHERE workspace_id=? AND mission_action_id=?",
                    workspace.getId(), action.getId());
            assertEquals("mission", row.get("proposal_origin"));
            assertNull(row.get("proposal_key"));
            assertNull(row.get("proposal_ordinal"));
            assertNull(row.get("proposal_prepared_at"));
        }
    }

    @Test
    void independentFirstApprovalCanStaleSiblingWithoutRefreshingIt() {
        var fixture = frozen("update_record_fields", "assign_owner");
        var first = fixture.selected().getFirst();
        var sibling = fixture.selected().getLast();
        var preparedAt = sibling.getPreparedAt();
        var response = decisions.decide(fixture.runId(), request(fixture, "approve", first, sibling));
        assertEquals(List.of("executed", "refused"), response.items().stream().map(item -> item.outcome()).toList());
        assertEquals("Prepared industry", companyMapper.getCompanyById(workspace.getId(), primary.getId()).getIndustry());
        assertEquals(preparedAt, actions.getById(workspace.getId(), fixture.runId(), sibling.getId()).getPreparedAt());
        assertEquals("proposed", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), sibling.getId()).getStatus());
        decisions.decide(fixture.runId(), request(fixture, "discard", sibling));
        assertEquals("done", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
    }

    @Test
    void waitingReviewOutlivesExecutionDeadline() {
        var fixture = frozen("assign_owner");
        var run = runs.getRun(workspace.getId(), fixture.runId());
        clock.set(run.getExecutionDeadlineAt().toInstant(ZoneOffset.UTC).plusSeconds(1));
        assertFalse(persistence.expire(run));
        assertEquals("open", reads.item(fixture.runId(), fixture.selected().getFirst().getActionSlot()).card().reviewState());
        assertTrue(runs.expiredRuns(workspace.getId(), 25).stream().noneMatch(item -> item.getId() == run.getId()));
    }

    @Test
    void expiredReviewRefusesWithSweeperStopped() {
        var fixture = frozen("assign_owner");
        var run = runs.getRun(workspace.getId(), fixture.runId());
        clock.set(run.getReviewExpiresAt().toInstant(ZoneOffset.UTC));
        var response = decisions.decide(fixture.runId(), request(fixture, "approve", fixture.selected().getFirst()));
        assertEquals("refused", response.items().getFirst().outcome());
        assertPending(fixture);
        assertEquals("expired", reads.item(fixture.runId(), fixture.selected().getFirst().getActionSlot()).card().reviewState());
        assertEquals(1, reads.output(fixture.runId()).proposalSet().derivedCounts().withdrawn());
    }

    @Test
    void cancelWithdrawsEveryPendingSlotWithoutCheckpointReferences() {
        var fixture = frozen("assign_owner", "update_record_fields");
        var run = runs.getRun(workspace.getId(), fixture.runId());
        assertEquals(2, fixture.selected().size());
        service.control(fixture.runId(), new AiAgentRunControlRequest(Long.toString(run.getStateVersion())), "cancel");
        for (var action : fixture.selected()) {
            var call = chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), action.getId());
            assertEquals("failed", call.getStatus());
            assertEquals("mission_withdrawn", json.readTree(call.getResultJson()).path("reason").asString());
        }
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
    }

    @Test
    void discardDoesNotRequireDomainEditPermissionOrTargetResolution() {
        var fixture = frozen("assign_owner");
        jdbc.update("INSERT INTO workspace_role (workspace_id,name) VALUES (?, 'Mission review only')", workspace.getId());
        Integer role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id=? AND name='Mission review only'", Integer.class, workspace.getId());
        assertNotNull(role);
        jdbc.update("INSERT INTO workspace_role_permission (workspace_role_id,permission) VALUES (?,'AI_USE')", role);
        jdbc.update("UPDATE workspace_member SET role='member',role_id=? WHERE workspace_id=? AND user_id=?", role, workspace.getId(), currentUser.getId());
        jdbc.update("DELETE FROM company WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId());
        var response = decisions.decide(fixture.runId(), request(fixture, "discard", fixture.selected().getFirst()));
        assertTrue(response.contentWithheld());
        assertTrue(response.items().isEmpty());
        assertEquals("rejected", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
        assertEquals("done", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
    }

    @Test
    void archivedPrimaryRetainsMetadataAndSafeCancellation() {
        var fixture = frozen("assign_owner");
        jdbc.update("UPDATE company SET archived_at=CURRENT_TIMESTAMP WHERE workspace_id=? AND id=?",
                workspace.getId(), primary.getId());
        var metadata = service.get(fixture.runId());
        assertTrue(metadata.contentWithheld());
        assertNull(metadata.goal());
        assertNull(metadata.proposalCounts());
        assertEquals(List.of("cancel"), metadata.availableActions());
        var listed = service.list(20, fixture.sessionId(), null, null).items();
        assertEquals(1, listed.size());
        assertTrue(listed.getFirst().contentWithheld());
        service.control(fixture.runId(), new AiAgentRunControlRequest(metadata.stateVersion()), "cancel");
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals("failed", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
    }

    @Test
    void unsupportedRuntimeRetainsMetadataAndCancellationWithdrawsCanonicalSources() {
        var fixture = frozen("assign_owner");
        jdbc.update("UPDATE ai_agent_run SET runtime_contract_version=4,mission_type='future_preparation_v1',checkpoint_schema_version=99 WHERE workspace_id=? AND id=?",
                workspace.getId(), fixture.runId());
        var metadata = service.get(fixture.runId());
        assertTrue(metadata.contentWithheld());
        assertTrue(metadata.availableActions().contains("cancel"));
        service.control(fixture.runId(), new AiAgentRunControlRequest(metadata.stateVersion()), "cancel");
        assertEquals("cancelled", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals("failed", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
    }

    @Test
    void lastTerminalDecisionCompletesWithoutProviderInvocation() {
        var fixture = frozen("assign_owner");
        var before = runs.listSegments(workspace.getId(), fixture.runId()).size();
        var response = decisions.decide(fixture.runId(), request(fixture, "discard", fixture.selected().getFirst()));
        assertEquals("rejected", response.items().getFirst().outcome());
        assertEquals("done", runs.getRun(workspace.getId(), fixture.runId()).getStatus());
        assertEquals(before, runs.listSegments(workspace.getId(), fixture.runId()).size());
    }

    @Test
    void terminalRetryReturnsActualRejectedWithdrawnAndUndoneOutcomes() {
        var fixture = frozen("assign_owner");
        var selected = fixture.selected().getFirst();
        decisions.decide(fixture.runId(), request(fixture, "discard", selected));
        clock.set(runs.getRun(workspace.getId(), fixture.runId()).getReviewExpiresAt().toInstant(ZoneOffset.UTC));
        assertEquals("rejected", decisions.decide(fixture.runId(), request(fixture, "approve", selected)).items().getFirst().outcome());
        var withdrawn = frozen("assign_owner");
        var live = runs.getRun(workspace.getId(), withdrawn.runId());
        persistence.control(live, live.getStateVersion(), "cancel");
        assertEquals("withdrawn", decisions.decide(withdrawn.runId(), request(withdrawn, "approve", withdrawn.selected().getFirst())).items().getFirst().outcome());
        var undone = frozen("assign_owner");
        var prior = undone.selected().getFirst();
        jdbc.update("UPDATE ai_chat_tool_call SET status='executed',result_json=? WHERE workspace_id=? AND mission_action_id=?",
                json.writeValueAsString(java.util.Map.of("undo", java.util.Map.of("status", "undone", "expiresAt", originalNow.plusSeconds(600).toString()))),
                workspace.getId(), prior.getId());
        assertEquals("undone", decisions.decide(undone.runId(), request(undone, "approve", prior)).items().getFirst().outcome());
        assertEquals(1, reads.output(undone.runId()).proposalSet().derivedCounts().undone());
    }
}
