package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAgentProposalReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiAgentActionMapper actions = mock(AiAgentActionMapper.class);
    private final AiAgentProposalSetPort proposals = mock(AiAgentProposalSetPort.class);
    private final AiAssistantToolCallReadService cards = mock(AiAssistantToolCallReadService.class);
    private final AiAssistantToolExecutor tools = mock(AiAssistantToolExecutor.class);
    private final AiAgentProposalReadService service = spy(new AiAgentProposalReadService(workspaces, chat, runs,
            actions, proposals, cards, mock(AiChatCitationProjector.class), tools,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC)));
    private final AiAgentRun run = new AiAgentRun();
    private final AiChatSession session = new AiChatSession();
    private final AiAgentAction action = new AiAgentAction();

    @BeforeEach
    void setUp() {
        run.setId(31);
        run.setWorkspaceId(7);
        run.setSessionId(13);
        run.setOwnerUserId(11);
        run.setMissionType("record_preparation_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(3);
        run.setLocale("en");
        run.setPrivacyGeneration(4);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":41}");
        run.setScopeFingerprint(AiAgentCapabilityGate.hash(run.getScopeJson()));
        new AiAgentContinuationCodec().initialize(run);
        run.setProposalMembershipRevision(3);
        run.setProposalFrozenAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        session.setId(13);
        session.setWorkspaceId(7);
        session.setCreatedByUserId(11);
        session.setStatus("active");
        session.setVisibility("private");
        action.setId(17);
        action.setActionSlot(UUID.randomUUID().toString());
        action.setPreparationId(UUID.randomUUID().toString());
        action.setOutputOrdinal(1);
        action.setPreparedAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(workspaces.isMember(7, 11)).thenReturn(true);
        when(runs.getRun(7, 31)).thenReturn(run);
        when(runs.lockRunForShare(7, 31)).thenReturn(run);
        when(chat.getMissionSessionForShare(7, 11, 13)).thenReturn(session);
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(chat.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
        when(actions.getBySlot(7, 31, action.getActionSlot())).thenReturn(action);
    }

    @Test
    void inaccessiblePrimaryReturnsWithheldMetadataWithoutEscapingTheParticipant() {
        when(tools.pageContext(anyList(), any())).thenThrow(
                new ooo.klae.connex.backend.exceptions.ResourceNotFoundException("Company not found"));
        var metadata = assertDoesNotThrow(() -> service.metadata(run));
        org.junit.jupiter.api.Assertions.assertTrue(metadata.contentWithheld());
        org.junit.jupiter.api.Assertions.assertNull(metadata.counts());
    }

    @Test
    void malformedSourcesRefuseKnownItemBeforeCardProjection() {
        doReturn(new AiAgentProposalReadService.Authorized(run, session)).when(service).authorize(31);
        when(proposals.readFrozen(run)).thenThrow(new IllegalArgumentException("mission_continuation_refused"));
        assertThrows(AiAgentGoneException.class, () -> service.item(31, action.getActionSlot()));
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void completeSourceInventoryIsValidatedBeforeAnyBatchItemCanExecute() {
        when(proposals.readFrozen(run)).thenThrow(new IllegalArgumentException("mission_continuation_refused"));
        assertThrows(AiAgentGoneException.class, () -> service.validateBatch(31, request()));
        verify(tools, never()).pageContext(anyList(), any());
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void discardPrevalidationChecksProvenanceWithoutResolvingDomainTargets() {
        when(proposals.readFrozen(run)).thenReturn(List.of(action));
        assertDoesNotThrow(() -> service.validateBatch(31, request()));
        verify(proposals).readFrozen(run);
        verify(tools, never()).pageContext(anyList(), any());
    }

    private AiAgentProposalDecisionRequest request() {
        return new AiAgentProposalDecisionRequest("3", List.of(new AiAgentProposalDecisionRequest.Item(
                action.getActionSlot(), action.getPreparationId(), "discard")));
    }
}
