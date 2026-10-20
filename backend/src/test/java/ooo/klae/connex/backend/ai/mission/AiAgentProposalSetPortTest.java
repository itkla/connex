package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAgentProposalSetPortTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private final AiAgentActionMapper actions = mock(AiAgentActionMapper.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final AiAgentSingletonProposalSetPort port = new AiAgentSingletonProposalSetPort(actions, runs, chat,
            mock(AiAssistantWriteToolService.class), JsonMapper.builder().build());
    private final AiAgentRun run = new AiAgentRun();

    @BeforeEach
    void setUp() {
        run.setId(31);
        run.setWorkspaceId(7);
        run.setMissionType("record_preparation_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(3);
        run.setPrivacyGeneration(4);
        run.setHardDeadlineAt(LocalDateTime.ofInstant(NOW.plusSeconds(900).plusSeconds(72 * 3600), ZoneOffset.UTC));
    }

    @Test
    void semanticSlotSurvivesPlanReplacementAndOutputReordering() {
        var inventory = new ArrayList<AiAgentAction>();
        for (String tool : AiAgentMissionCatalog.confirmTools("person").stream().sorted().toList()) {
            var action = action(inventory.size() + 1, tool, false);
            inventory.add(action);
            when(actions.lockAction(7, 31, action.getId())).thenReturn(action);
        }
        when(actions.listByRoot(7, 31)).thenReturn(inventory);
        run.setPlanRevision(7);
        List<AiAgentAction> reused = port.allocateSlots(run, new AiAgentScopePolicy("person", 41));
        assertEquals(inventory.stream().map(AiAgentAction::getActionSlot).toList(),
                reused.stream().map(AiAgentAction::getActionSlot).toList());
        assertEquals(1, reused.getFirst().getPlanRevision());
        assertEquals("p0001", reused.getFirst().getPlanItemKey());
        verify(actions, never()).insertSlot(any());
    }

    @Test
    void sameIntentReplayKeepsOriginalPinsAndPreparationAnchor() {
        var bound = action(1, "assign_owner", true);
        var original = canonical(bound);
        when(actions.lockAction(7, 31, 1)).thenReturn(bound);
        when(chat.lockMissionToolCall(7, 31, 1)).thenReturn(original);
        var replay = action(1, "assign_owner", true);
        replay.setPreparedAt(bound.getPreparedAt().plusDays(1));
        replay.setPreparationId(UUID.randomUUID().toString());
        var result = port.publishPrepared(run, policy(), replay,
                new AiAssistantPreparedWrite("assign_owner", ToolTier.CONFIRM, "person", 41, "different fresh pins"));
        assertSame(original, result);
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), bound.getPreparedAt());
        verify(actions, never()).bindPreparation(any());
        verify(chat, never()).insertMissionToolCall(any());
    }

    @Test
    void changedIntentCannotAllocateReplacementSlot() {
        var bound = action(1, "assign_owner", true);
        when(actions.lockAction(7, 31, 1)).thenReturn(bound);
        var changed = action(1, "assign_owner", true);
        changed.setIntentFingerprint(new byte[] {2});
        assertEquals("action_intent_conflict", assertThrows(ConflictException.class,
                () -> port.publishPrepared(run, policy(), changed,
                        new AiAssistantPreparedWrite("assign_owner", ToolTier.CONFIRM, "person", 41, "{}"))).getMessage());
        verify(actions, never()).insertSlot(any());
        verify(actions, never()).bindPreparation(any());
    }

    @Test
    void unusedSlotsAreExcludedButBoundSlotsCannotBeOmitted() {
        var bound = action(1, "assign_owner", true);
        var unused = action(2, "remove_tag", false);
        when(actions.listByRoot(7, 31)).thenReturn(List.of(bound, unused));
        assertEquals("mission_membership_conflict", assertThrows(ConflictException.class,
                () -> port.freeze(run, List.of(unused), NOW.plusSeconds(2))).getMessage());
        when(chat.getMissionToolCall(7, 31, 1)).thenReturn(canonical(bound));
        when(actions.assignOutputOrdinal(7, 31, 1, 1)).thenReturn(1);
        port.freeze(run, List.of(bound, unused), NOW.plusSeconds(2));
        assertNotNull(run.getReviewExpiresAt());
        verify(actions, never()).assignOutputOrdinal(7, 31, 2, 2);
        assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(72 * 3600), ZoneOffset.UTC), run.getReviewExpiresAt());
    }

    @Test
    void collectionCeilingIsIndependentOfPlanAndP133Limits() {
        var inventory = new ArrayList<AiAgentAction>();
        for (int index = 1; index <= 50; index++) {
            var action = action(index, "assign_owner", true);
            inventory.add(action);
            when(chat.getMissionToolCall(7, 31, index)).thenReturn(canonical(action));
            when(actions.assignOutputOrdinal(7, 31, index, index)).thenReturn(1);
        }
        when(actions.listByRoot(7, 31)).thenReturn(inventory);
        port.freeze(run, inventory, NOW.plusSeconds(2));
        verify(actions).assignOutputOrdinal(7, 31, 50, 50);
        assertEquals(1, run.getProposalMembershipRevision());
    }

    @Test
    void emptyFreezeCompletesWithoutReviewDeadline() {
        when(actions.listByRoot(7, 31)).thenReturn(List.of());
        port.freeze(run, List.of(), NOW);
        assertNotNull(run.getProposalFrozenAt());
        assertEquals(1, run.getProposalMembershipRevision());
        assertEquals(null, run.getReviewExpiresAt());
        assertEquals(null, run.getProposalFirstPreparedAt());
    }

    @Test
    void missingCanonicalBindingFailsClosedWithoutNullDereference() {
        var bound = action(1, "assign_owner", true);
        when(actions.listByRoot(7, 31)).thenReturn(List.of(bound));
        when(actions.lockAction(7, 31, 1)).thenReturn(bound);
        assertThrows(ConflictException.class, () -> port.lockInventory(run));
    }

    private static AiAgentAction action(int id, String tool, boolean bound) {
        var action = new AiAgentAction();
        action.setId(id);
        action.setWorkspaceId(7);
        action.setRootRunId(31);
        action.setProducerRunId(31);
        action.setActionSlot(UUID.randomUUID().toString());
        action.setSemanticSlotKey("person:41:" + tool);
        action.setTargetKind("person");
        action.setTargetId(41);
        action.setToolName(tool);
        action.setPlanItemKey("p0001");
        action.setPlanRevision(1);
        action.setPrivacyGeneration(4);
        if (bound) {
            action.setPreparationId(UUID.randomUUID().toString());
            action.setIntentFingerprint(new byte[] {1});
            action.setPreparedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            action.setSourceObservedAt(action.getPreparedAt().minusSeconds(1));
            action.setSourceVersionsJson(new AiAgentSourceObservationsCodec().encode(new AiAgentSourceObservations(1,
                    List.of(new AiAgentSourceObservations.Observation("person", 41, "record", 0, "a".repeat(64), NOW.minusSeconds(1))))));
            action.setOriginSegmentId(37);
            action.setOriginExecutionGeneration(1L);
        }
        return action;
    }

    private static AiChatToolCall canonical(AiAgentAction action) {
        var call = new AiChatToolCall();
        call.setId(action.getId());
        call.setToolName(action.getToolName());
        call.setStatus("proposed");
        call.setServerProposal(true);
        call.setProposalOrigin("mission");
        call.setArtifactOrigin("mission");
        call.setMissionRunId(31);
        call.setMissionActionId(action.getId());
        call.setPrivacyGeneration(4L);
        call.setArgumentsJson("original pins");
        return call;
    }

    private static AiAgentExecutionPolicy policy() {
        return new AiAgentExecutionPolicy(31, 37, 1, null, null, new AiAgentScopePolicy("person", 41),
                4, NOW.plusSeconds(180), null, null, null, null, 3, 1, 1, null, true, 65536);
    }
}
