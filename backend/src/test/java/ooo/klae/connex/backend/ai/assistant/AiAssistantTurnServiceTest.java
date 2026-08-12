package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;

class AiAssistantTurnServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());

    private WorkspaceService workspaceService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiChatTurnPersistenceService persistenceService;
    private AiGenerationService generationService;
    private AiChatAgentLoopService agentLoopService;
    private AiChatTurnTerminalCoordinator terminalCoordinator;
    private AiAssistantTurnService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        generationService = mock(AiGenerationService.class);
        agentLoopService = mock(AiChatAgentLoopService.class);
        terminalCoordinator = mock(AiChatTurnTerminalCoordinator.class);
        service = serviceWith(mock(AiFeatureGate.class));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(restrictionEpoch.current(TURN.workspaceId())).thenReturn(TURN.restrictionEpoch());
        when(persistenceService.queue(
                eq(TURN.sessionId()), any(AiChatTurnCreateRequest.class),
                eq(TURN.restrictionEpoch()))).thenReturn(TURN);
        when(terminalCoordinator.listener(TURN)).thenReturn((outcome, reason) -> true);
    }

    @Test
    void disabledMasterSwitchRejectsBeforeDurableOrGenerationAdmission() {
        service = serviceWith(featureGate(new AiProperties()));

        ForbiddenException unavailable = assertThrows(
                ForbiddenException.class,
                () -> service.start(
                        TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of())));

        assertEquals("AI features are not available", unavailable.getMessage());
        verifyNoAdmission();
    }

    @Test
    void disabledAssistantFlagRejectsBeforeDurableOrGenerationAdmission() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getFeatures().put(AiFeature.ASSISTANT_CHAT, false);
        service = serviceWith(featureGate(properties));

        ForbiddenException unavailable = assertThrows(
                ForbiddenException.class,
                () -> service.start(
                        TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of())));

        assertEquals("AI features are not available", unavailable.getMessage());
        verifyNoAdmission();
    }

    @Test
    void generationCapacityLeavesTheCommittedTurnFailedBeforeReturning429() {
        when(generationService.startAtRestrictionEpoch(
                eq(AiFeature.ASSISTANT_CHAT), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new TooManyRequestsException("busy"));

        assertThrows(TooManyRequestsException.class, () -> service.start(
                TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of())));

        verify(terminalCoordinator).generationCapacity(TURN);
    }

    @Test
    void epochChangeLeavesTheCommittedTurnFailedBeforeReturning409() {
        when(generationService.startAtRestrictionEpoch(
                eq(AiFeature.ASSISTANT_CHAT), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new ConflictException("changed"));

        assertThrows(ConflictException.class, () -> service.start(
                TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of())));

        verify(terminalCoordinator).restrictionsChanged(TURN);
    }

    private AiAssistantTurnService serviceWith(AiFeatureGate gate) {
        return new AiAssistantTurnService(
                workspaceService,
                gate,
                restrictionEpoch,
                persistenceService,
                generationService,
                agentLoopService,
                terminalCoordinator);
    }

    private AiFeatureGate featureGate(AiProperties properties) {
        AiWorkspaceGovernanceService governanceService = mock(AiWorkspaceGovernanceService.class);
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        return new AiFeatureGate(
                properties,
                workspaceService,
                new StaticListableBeanFactory().getBeanProvider(AiProviderReadiness.class),
                governanceService);
    }

    private void verifyNoAdmission() {
        verify(restrictionEpoch, never()).current(anyInt());
        verify(persistenceService, never()).queue(anyInt(), any(), anyLong());
        verify(generationService, never()).startAtRestrictionEpoch(
                any(), any(), any(), any(), any(), anyLong(), any());
    }
}
