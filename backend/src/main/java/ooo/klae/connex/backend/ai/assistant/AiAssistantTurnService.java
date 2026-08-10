package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiGenerationTerminalListener;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnDto;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Request-thread coordinator for durable turn preparation and bounded generation admission. */
@Service
@RequiredArgsConstructor
public class AiAssistantTurnService {
    private static final Set<String> PAGE_CONTEXT_KINDS = Set.of("person", "company", "deal");

    private final WorkspaceService workspaceService;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiGenerationService generationService;
    private final AiChatAgentLoopService agentLoopService;
    private final AiChatTurnTerminalCoordinator terminalCoordinator;

    /** Starts one whole assistant turn after committing its durable queued state. */
    @RequirePermission(Permission.AI_USE)
    public AiChatTurnAcceptedDto start(int sessionId, AiChatTurnCreateRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()
                || request.content().length() > 16_000 || request.pageContext().size() > 10
                || request.pageContext().stream().anyMatch(context -> context == null
                        || context.id() <= 0
                        || context.kind() == null
                        || !PAGE_CONTEXT_KINDS.contains(context.kind()))) {
            throw new BadRequestException("Assistant turn request is invalid");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long expectedRestrictionEpoch = restrictionEpoch.current(workspaceId);
        AiChatQueuedTurn turn = persistenceService.queue(
                sessionId, request, expectedRestrictionEpoch);
        AiGenerationTerminalListener terminalListener = terminalCoordinator.listener(turn);
        AiGenerationStatusDto generation;
        try {
            generation = generationService.startAtRestrictionEpoch(
                    AiFeature.ASSISTANT_CHAT,
                    new TurnIdentity(turn.turnId()),
                    Set.of(Permission.AI_USE),
                    new AiChatTurnGenerationResult(turn.turnId(), "unavailable"),
                    () -> agentLoopService.run(turn),
                    expectedRestrictionEpoch,
                    terminalListener);
        } catch (TooManyRequestsException exception) {
            terminalCoordinator.generationCapacity(turn);
            throw exception;
        } catch (ConflictException exception) {
            terminalCoordinator.restrictionsChanged(turn);
            throw exception;
        } catch (RuntimeException exception) {
            terminalListener.onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED,
                    "provider_error");
            throw exception;
        }
        if (!"accepted".equals(generation.status()) && !"running".equals(generation.status())) {
            terminalListener.onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED,
                    "access_revoked");
            throw new ForbiddenException("Requires the AI_USE permission in this workspace");
        }
        return new AiChatTurnAcceptedDto(
                turn.turnId(), turn.sessionId(), generation.handle(), generation.status());
    }

    /** Returns one authorized durable turn after applying lazy expiry. */
    @RequirePermission(Permission.AI_USE)
    public AiChatTurnDto get(int sessionId, int turnId) {
        return AiChatTurnDto.from(persistenceService.readTurn(sessionId, turnId));
    }

    private record TurnIdentity(int turnId) {
    }
}
