package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiGenerationTerminalListener;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Routes generation terminal callbacks to the owning tenant catalog and initiating user's queue. */
@Component
@RequiredArgsConstructor
public class AiChatTurnTerminalCoordinator {
    private static final String INTERNAL_ERROR = "internal_error";
    private static final Set<String> FAILED_REASONS = Set.of(
            "provider_error",
            "image_input_unsupported",
            "quota_exhausted",
            "budget_exhausted",
            "tool_result_budget_exhausted",
            "org_invocation_quota_exhausted",
            "invocation_capacity_exhausted",
            "malformed_output",
            "schema_repair_failed",
            "attachment_auto_write_blocked",
            "no_progress",
            "agent_backstop_exceeded",
            "step_cap_exceeded",
            "workspace_disabled",
            "generation_capacity",
            "restrictions_changed",
            "access_revoked",
            INTERNAL_ERROR);
    private static final Set<String> TIMED_OUT_REASONS = Set.of(
            "generation_timeout",
            "turn_deadline_exceeded",
            "provider_idle_timeout");

    private final TenantWorkScope tenantWorkScope;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiChatRealtimeDispatcher realtimeDispatcher;

    /** Creates the durable terminal listener for one committed turn. */
    public AiGenerationTerminalListener listener(AiChatQueuedTurn turn) {
        return (outcome, reason) -> complete(turn, outcome, reason);
    }

    /** Marks a committed turn failed after generation registry capacity rejects it. */
    public void generationCapacity(AiChatQueuedTurn turn) {
        complete(turn, AiGenerationTaskResult.Outcome.FAILED, "generation_capacity");
    }

    /** Marks a committed turn failed when its prepared restriction epoch is no longer current. */
    public void restrictionsChanged(AiChatQueuedTurn turn) {
        complete(turn, AiGenerationTaskResult.Outcome.FAILED, "restrictions_changed");
    }

    private boolean complete(
            AiChatQueuedTurn turn,
            AiGenerationTaskResult.Outcome outcome,
            String reason) {
        if (outcome == AiGenerationTaskResult.Outcome.RESOLVED) {
            int terminalOffset = tenantWorkScope.inWorkspace(
                    turn.workspaceId(), () -> persistenceService.terminalOffset(turn));
            publish(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    terminalOffset, "terminal", null, "resolved", null));
            return true;
        }
        String status = outcome == AiGenerationTaskResult.Outcome.TIMED_OUT
                ? "timed_out"
                : "failed";
        String stableReason = stableReason(outcome, reason);
        boolean changed = tenantWorkScope.inWorkspace(
                turn.workspaceId(),
                () -> persistenceService.markTerminal(turn, status, stableReason));
        if (changed) {
            int terminalOffset = tenantWorkScope.inWorkspace(
                    turn.workspaceId(), () -> persistenceService.terminalOffset(turn));
            publish(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    terminalOffset, "terminal", null, status, stableReason));
        }
        return changed;
    }

    private static String stableReason(
            AiGenerationTaskResult.Outcome outcome, String reason) {
        if (outcome == AiGenerationTaskResult.Outcome.TIMED_OUT) {
            return reason != null && TIMED_OUT_REASONS.contains(reason)
                    ? reason
                    : "generation_timeout";
        }
        if ("generation_failed".equals(reason)) {
            return INTERNAL_ERROR;
        }
        return reason != null && FAILED_REASONS.contains(reason)
                ? reason
                : INTERNAL_ERROR;
    }

    private void publish(AiChatQueuedTurn turn, AiChatStepFrameDto frame) {
        realtimeDispatcher.sessionNow(turn.workspaceId(), turn.sessionId(), frame);
    }
}
