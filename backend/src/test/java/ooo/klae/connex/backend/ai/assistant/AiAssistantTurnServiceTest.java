package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAssistantTurnServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 23, List.of());

    private AiGenerationService generationService;
    private AiChatTurnTerminalCoordinator terminalCoordinator;
    private AiAssistantTurnService service;

    @BeforeEach
    void setUp() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AiRestrictionEpoch restrictionEpoch = mock(AiRestrictionEpoch.class);
        AiChatTurnPersistenceService persistenceService = mock(AiChatTurnPersistenceService.class);
        generationService = mock(AiGenerationService.class);
        AiChatAgentLoopService agentLoopService = mock(AiChatAgentLoopService.class);
        terminalCoordinator = mock(AiChatTurnTerminalCoordinator.class);
        service = new AiAssistantTurnService(
                workspaceService,
                restrictionEpoch,
                persistenceService,
                generationService,
                agentLoopService,
                terminalCoordinator);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(restrictionEpoch.current(TURN.workspaceId())).thenReturn(TURN.restrictionEpoch());
        when(persistenceService.queue(
                eq(TURN.sessionId()), any(AiChatTurnCreateRequest.class),
                eq(TURN.restrictionEpoch()))).thenReturn(TURN);
        when(terminalCoordinator.listener(TURN)).thenReturn((outcome, reason) -> {});
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
}
