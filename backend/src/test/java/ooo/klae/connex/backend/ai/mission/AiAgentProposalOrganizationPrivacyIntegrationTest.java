package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.services.DataSubjectRequestService;
import ooo.klae.connex.backend.services.DataSubjectRequestSnapshot;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Exercises runtime-3 holdings at organization invalidation, bootstrap and acknowledged DSR boundaries. */
class AiAgentProposalOrganizationPrivacyIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    @Autowired private AiAssistantPrivacyService organizationPrivacy;
    @Autowired private AiAssistantPrivacyControlMapper control;
    @Autowired private TenantLifecycleMapper lifecycle;
    @Autowired private AiAgentDsrPurgeService purge;
    @Autowired private DataSubjectRequestService requests;
    @Autowired private DataSubjectRequestMapper requestRows;
    @Autowired private AiAgentDsrControlMapper receipts;

    @AfterEach
    void removeOrganizationRequestFixtures() {
        jdbc.update("DELETE FROM data_subject_request WHERE org_id=?", workspace.getOrgId());
        jdbc.update("DELETE FROM person WHERE workspace_id=?", workspace.getId());
        jdbc.update("DELETE FROM org_member WHERE org_id=?", workspace.getOrgId());
    }

    @Test
    void organizationInvalidationWithholdsFrozenProposalsDecisionsAndExportBeforeErasure() {
        Fixture fixture = frozen("update_record_fields", "assign_owner");
        assertEquals(2, reads.output(fixture.runId()).proposalSet().items().size());
        assertTrue(exportsAny("ai_agent_action"));
        var decision = request(fixture, "approve", fixture.selected().getFirst());
        String operation = invalidateOrganization();

        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertTrue(actions.countByRoot(workspace.getId(), fixture.runId()) > 0);
        assertThrows(AiAgentGoneException.class, () -> reads.output(fixture.runId()));
        assertThrows(AiAgentGoneException.class, () -> reads.item(fixture.runId(), fixture.selected().getFirst().getActionSlot()));
        assertThrows(AiAgentGoneException.class, () -> decisions.decide(fixture.runId(), decision));
        assertFalse(exportsAny("ai_agent_action"));
        assertFalse(exportsAny("ai_chat_tool_call"));
        assertEquals("proposed", chatMapper.getMissionToolCallForMaintenance(
                workspace.getId(), fixture.runId(), fixture.selected().getFirst().getId()).getStatus());
        assertNull(chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(),
                fixture.selected().getFirst().getId(), organizationPrivacy.current(workspace.getOrgId())));

        finish(operation);
        assertErased(fixture, operation, 1);
    }

    @Test
    void bootstrapReopensCompletedEvidenceAndErasesRestoredGenerationZeroFrozenProposals() {
        String operation = control.getFence(workspace.getOrgId()).getBootstrapOperationId();
        finish(operation);
        Fixture fixture = frozen("update_record_fields", "assign_owner");
        assertTrue(exportsAny("ai_agent_action"));
        transaction().executeWithoutResult(status -> {
            jdbc.update("UPDATE ai_chat_session SET organization_privacy_generation=0 WHERE workspace_id=? AND id=?",
                    workspace.getId(), fixture.sessionId());
            jdbc.update("UPDATE ai_agent_run SET organization_privacy_generation=0 WHERE workspace_id=? AND id=?",
                    workspace.getId(), fixture.runId());
            jdbc.update("UPDATE ai_agent_segment SET organization_privacy_generation=0 WHERE workspace_id=? AND run_id=?",
                    workspace.getId(), fixture.runId());
            jdbc.update("UPDATE ai_chat_message SET organization_privacy_generation=0 WHERE workspace_id=? AND session_id=?",
                    workspace.getId(), fixture.sessionId());
            jdbc.update("UPDATE ai_chat_turn SET organization_privacy_generation=0 WHERE workspace_id=? AND session_id=?",
                    workspace.getId(), fixture.sessionId());
            jdbc.update("UPDATE ai_chat_tool_call SET organization_privacy_generation=0 WHERE workspace_id=? AND mission_run_id=?",
                    workspace.getId(), fixture.runId());
        });

        assertThrows(AiAgentGoneException.class, () -> reads.output(fixture.runId()));
        assertThrows(AiAgentGoneException.class, () -> decisions.decide(fixture.runId(),
                request(fixture, "discard", fixture.selected().getFirst())));
        assertFalse(exportsAny("ai_agent_action"));
        privacyService.initialize(workspace.getId());
        assertEquals(operation, control.getFence(workspace.getOrgId()).getBootstrapOperationId());
        assertNull(privacyMapper.getWork(workspace.getId(), operation).getCompletedAt());

        finish(operation);
        assertErased(fixture, operation, 0);
        assertEquals(1, organizationPrivacy.current(workspace.getOrgId()));
    }

    @Test
    void acknowledgedDsrVersionTwoErasesFrozenRuntimeThreeHoldingsAndKeepsCurrentGeneration() {
        Fixture fixture = frozen("update_record_fields", "assign_owner");
        jdbc.update("INSERT INTO org_member (org_id,user_id,org_role) VALUES (?,?,'admin')",
                workspace.getOrgId(), currentUser.getId());
        approveForStepUp(currentUser);
        var person = newPerson(primary);
        DataSubjectRequestUpsertRequest workflow = new DataSubjectRequestUpsertRequest();
        workflow.setRequestType("cease_use");
        workflow.setStatus("received");
        workflow.setRequesterName("Organization mission erasure requester");
        workflow.setSubjectName("Organization mission erasure subject");
        workflow.setSubjectWorkspaceId(workspace.getId());
        workflow.setSubjectPersonId(person.getId());
        workflow.setReceivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        workflow.setIdentityVerifiedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        long requestId = requests.create(workspace.getOrgId(), currentUser.getId(), workflow).getId();
        var command = new AiAgentHoldingsPurgeRequest(UUID.randomUUID().toString(),
                DataSubjectRequestSnapshot.token(requestRows.findById(workspace.getOrgId(), requestId)),
                AiAgentPurgeAcceptance.ASSISTANT_SCOPE, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY);
        var accepted = purge.initiate(workspace.getOrgId(), requestId, currentUser.getId(), command);
        assertEquals(2, accepted.schemaVersion());
        assertEquals(AiAgentPurgeAcceptance.ASSISTANT_SCOPE, accepted.scope());
        assertEquals(AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY, accepted.cutoffPolicy());
        var authority = AiAgentPurgeAcceptance.from(receipts.findAcceptance(
                workspace.getOrgId(), requestId, accepted.operationId()), json);
        assertEquals(2, authority.schemaVersion());
        assertEquals(1L, authority.cutoffGeneration());
        assertEquals(2L, authority.invalidatedGeneration());
        assertThrows(AiAgentGoneException.class, () -> reads.output(fixture.runId()));
        assertFalse(exportsAny("ai_agent_action"));

        finish(accepted.operationId());
        purge.recoverAccepted();
        assertErased(fixture, accepted.operationId(), 1);
        assertNotNull(receipts.findCompletion(workspace.getOrgId(), requestId, accepted.operationId()));
        assertEquals("complete", purge.get(workspace.getOrgId(), requestId,
                currentUser.getId(), accepted.operationId()).state());
        assertEquals(2, organizationPrivacy.current(workspace.getOrgId()));
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
    }

    private String invalidateOrganization() {
        UUID operation = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateAssistantOrganization(
                workspace.getOrgId(), List.of(workspace.getId()), operation, "restriction", null, null));
        privacyService.reconcileAssistantOperations(workspace.getId());
        return operation.toString();
    }

    private boolean exportsAny(String table) {
        return Boolean.TRUE.equals(transaction().execute(status -> {
            try (var cursor = lifecycle.streamRows(workspace.getId(), TenantLifecycleRegistry.require(table),
                    organizationPrivacy.current(workspace.getOrgId()))) {
                return cursor.iterator().hasNext();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }));
    }

    private void assertErased(Fixture fixture, String operation, long cutoff) {
        assertEquals(0, actions.countByRoot(workspace.getId(), fixture.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id=? AND mission_run_id=?",
                Integer.class, workspace.getId(), fixture.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE workspace_id=? AND mission_run_id=?",
                Integer.class, workspace.getId(), fixture.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE workspace_id=? AND id=?",
                Integer.class, workspace.getId(), fixture.sessionId()));
        var tombstone = runs.getRun(workspace.getId(), fixture.runId());
        assertNotNull(tombstone.getPurgedAt());
        assertEquals(0, tombstone.getProposalMembershipRevision());
        assertNull(tombstone.getProposalFrozenAt());
        assertNull(tombstone.getProposalFirstPreparedAt());
        assertNull(tombstone.getReviewExpiresAt());
        assertNull(tombstone.getReportMessageId());
        assertEquals(0, privacyMapper.allChatResidualCount(workspace.getId(), cutoff, operation));
    }
}
