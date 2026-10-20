package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantProposalExpiredException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService.PreliminaryPrincipals;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.dto.AiAssistantToolCallReadDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAgentProposalDecisionServiceTest {
    private final AiAgentProposalReadService reads = mock(AiAgentProposalReadService.class);
    private final AiAgentProposalSetPort proposals = mock(AiAgentProposalSetPort.class);
    private final AiAssistantWriteToolService writes = mock(AiAssistantWriteToolService.class);
    private final AiAgentActionMapper actions = mock(AiAgentActionMapper.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiAgentRealtimePublisher realtime = mock(AiAgentRealtimePublisher.class);
    private final AiAgentCapabilityGate gate = mock(AiAgentCapabilityGate.class);
    private final AiAgentProposalDecisionService transaction = mock(AiAgentProposalDecisionService.class);
    private final ObjectProvider<AiAgentProposalDecisionService> self = new ObjectProvider<>() {
        @Override
        public AiAgentProposalDecisionService getObject() {
            return transaction;
        }
    };
    private final AiAgentProposalDecisionService service = new AiAgentProposalDecisionService(reads, proposals,
            writes, actions, runs, chat, workspaces, new AiRestrictionEpoch(), realtime, gate,
            Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC), JsonMapper.builder().build(), self);
    private final AiAgentProposalDecisionRequest.Item first = item("approve");
    private final AiAgentProposalDecisionRequest.Item second = item("approve");

    @BeforeEach
    void setUp() {
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(reads.currentVersion(31)).thenReturn("17");
        prepare(first, 1);
        prepare(second, 2);
    }

    @Test
    void pendingDecisionRequiresFrozenOwnerRevisionPreparationAndPrivacy() {
        var request = request(first, second);
        doThrow(new ConflictException("mission_preparation_conflict")).when(reads).validateBatch(31, request);
        assertThrows(ConflictException.class, () -> service.decide(31, request));
        verify(transaction, never()).decideOne(anyInt(), anyString(), any(), any());
    }

    @Test
    void firstCommittedResultSurvivesSecondConflictAndStopsBatch() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenReturn("executed").thenThrow(new ConflictException("mission_preparation_conflict"));
        var third = item("discard");
        var result = service.decide(31, request(first, second, third));
        assertFalse(result.complete());
        assertEquals(List.of("executed", "refused", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
        assertEquals("proposal_conflict", result.stopReason());
        assertEquals(3, result.items().size());
    }

    @Test
    void lostDomainPermissionRefusesAttemptedItemAndSkipsRemainingSelection() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenReturn("executed").thenThrow(new ForbiddenException("permission revoked"));
        var result = service.decide(31, request(first, second, item("discard")));
        assertFalse(result.contentWithheld());
        assertEquals("access_lost", result.stopReason());
        assertEquals(List.of("executed", "refused", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
    }

    @Test
    void infrastructureFailureStopsBatchAfterCommittedResults() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenReturn("executed").thenThrow(new IllegalStateException("connection lost"));
        when(reads.item(31, second.actionSlot())).thenReturn(projected(second, "proposed", "open"));
        var result = service.decide(31, request(first, second, item("discard")));
        assertEquals(List.of("executed", "refused", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
        assertEquals("infrastructure_failure", result.stopReason());
    }

    @Test
    void expiryAtMutationAdmissionRefusesTheItemWithoutCanonicalReconciliation() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenReturn("executed").thenThrow(new AiAssistantProposalExpiredException());
        var result = service.decide(31, request(first, second, item("discard")));
        assertFalse(result.complete());
        assertEquals("mission_review_expired", result.stopReason());
        assertEquals(List.of("executed", "refused", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
        assertEquals("mission_review_expired", result.items().get(1).code());
        verify(reads, never()).item(anyInt(), anyString());
    }

    @Test
    void infrastructureReconciliationDoesNotTurnAnExpiredPendingItemIntoWithdrawal() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("commit acknowledgment lost"));
        when(reads.item(31, first.actionSlot())).thenReturn(projected(first, "proposed", "expired"));
        var result = service.decide(31, request(first, second));
        assertEquals("infrastructure_failure", result.stopReason());
        assertEquals(List.of("refused", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
    }

    @Test
    void commitSuccessFollowedByExceptionReconcilesFreshCanonicalOutcome() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("commit acknowledgment lost"));
        when(reads.item(31, first.actionSlot())).thenReturn(projected(first, "executed", "applied"));
        var result = service.decide(31, request(first, second));
        assertEquals("executed", result.items().getFirst().outcome());
        assertEquals("not_processed", result.items().getLast().outcome());
        assertFalse(result.complete());
        verify(reads).item(31, first.actionSlot());
    }

    @Test
    void failedCanonicalReconciliationReturnsIndeterminateAndDisarmsRemainingItems() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("commit acknowledgment lost"));
        when(reads.item(31, first.actionSlot())).thenThrow(new IllegalStateException("read unavailable"));
        var result = service.decide(31, request(first, second));
        assertEquals(List.of("indeterminate", "not_processed"),
                result.items().stream().map(item -> item.outcome()).toList());
        assertFalse(result.contentWithheld());
    }

    @Test
    void revocationBetweenItemsSuppressesPreviouslyCommittedPrivateOutcomes() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any()))
                .thenReturn("executed").thenThrow(new ForbiddenException("permission revoked"));
        when(reads.currentVersion(31)).thenThrow(new ForbiddenException("permission revoked"));
        var result = service.decide(31, request(first, second));
        assertTrue(result.contentWithheld());
        assertTrue(result.items().isEmpty());
        assertEquals("content_withheld", result.stopReason());
    }

    @Test
    void postCommitInvalidationFailureNeverDuplicatesAnItemOutcome() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any())).thenReturn("executed");
        when(runs.getRun(7, 31)).thenThrow(new IllegalStateException("invalidation lookup unavailable"));
        var result = service.decide(31, request(first));
        assertTrue(result.complete());
        assertEquals(1, result.items().size());
        assertEquals("executed", result.items().getFirst().outcome());
    }

    @Test
    void unavailableDisclosureAuthorityWithholdsCommittedOutcomes() {
        when(transaction.decideOne(anyInt(), anyString(), any(), any())).thenReturn("executed");
        when(reads.currentVersion(31)).thenThrow(new IllegalStateException("version read unavailable"));
        var result = service.decide(31, request(first));
        assertTrue(result.items().isEmpty());
        assertTrue(result.contentWithheld());
        assertEquals("content_withheld", result.stopReason());
    }

    @Test
    void discardDoesNotResolveDomainTargetsOrNamedPrincipals() {
        var discard = item("discard");
        when(transaction.decideOne(anyInt(), anyString(), any(), any())).thenReturn("rejected");
        var result = service.decide(31, request(discard));
        assertEquals("rejected", result.items().getFirst().outcome());
        verify(writes, never()).missionPrincipals(any());
        verify(actions, never()).getBySlot(7, 31, discard.actionSlot());
    }

    private void prepare(AiAgentProposalDecisionRequest.Item selected, int id) {
        var action = new AiAgentAction();
        action.setId(id);
        when(actions.getBySlot(7, 31, selected.actionSlot())).thenReturn(action);
        var call = new AiChatToolCall();
        when(chat.getMissionToolCall(7, 31, id)).thenReturn(call);
        when(writes.missionPrincipals(call)).thenReturn(new PreliminaryPrincipals(List.of()));
    }

    private static AiAgentProposalDecisionRequest.Item item(String decision) {
        return new AiAgentProposalDecisionRequest.Item(UUID.randomUUID().toString(), UUID.randomUUID().toString(), decision);
    }

    private static AiAgentProposalDecisionRequest request(AiAgentProposalDecisionRequest.Item... items) {
        return new AiAgentProposalDecisionRequest("3", List.of(items));
    }

    private static AiAgentOutputDto.ProposalItem projected(AiAgentProposalDecisionRequest.Item selected,
            String status, String reviewState) {
        var card = new AiAssistantToolCallReadDto(1, "assign_owner", "confirm", status, reviewState,
                null, null, null, null, null, List.of(), List.of(), null, null, 1, null, false, null, null, null);
        return new AiAgentOutputDto.ProposalItem(selected.actionSlot(), selected.expectedPreparationId(), 1,
                Instant.parse("2026-10-19T00:00:00Z"), card);
    }

}
