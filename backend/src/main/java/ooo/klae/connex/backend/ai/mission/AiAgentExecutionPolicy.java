package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiChatMessage;

/** Server-created execution binding; no client flag can enter this policy. */
public record AiAgentExecutionPolicy(int runId, int segmentId, long executionGeneration,
        AiChatQueuedTurn turn, AiChatMessage goal, AiAgentScopePolicy scope, int maxSteps,
        Instant deadline, AiRunLease lease, AiRunLeaseGuard ownership,
        AiAgentRunPersistenceService persistence, AiAgentCapabilityGate capability,
        int runtimeContractVersion, int segmentNo, int attemptNo,
        AiAgentContinuationCodec.Snapshot snapshot, boolean continuationCapable, long reservedTokens) {
    private static final ThreadLocal<AiAgentExecutionPolicy> CURRENT = new ThreadLocal<>();

    /** Preserves legacy server-created executions with their shipped single-segment behavior. */
    public AiAgentExecutionPolicy(int runId, int segmentId, long executionGeneration,
            AiChatQueuedTurn turn, AiChatMessage goal, AiAgentScopePolicy scope, int maxSteps,
            Instant deadline, AiRunLease lease, AiRunLeaseGuard ownership,
            AiAgentRunPersistenceService persistence, AiAgentCapabilityGate capability) {
        this(runId, segmentId, executionGeneration, turn, goal, scope, maxSteps, deadline, lease,
                ownership, persistence, capability, 1, 1, 1, null, false, 131072);
    }

    /** Returns the current server policy or null for an ordinary reactive execution. */
    public static AiAgentExecutionPolicy current() {
        return CURRENT.get();
    }

    /** Installs one policy only for the bounded execution and restores the thread afterward. */
    public <T> T execute(Supplier<T> action) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Nested mission execution is forbidden");
        }
        CURRENT.set(this);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    /** Requires exact turn ownership before an existing loop callback can use mission persistence. */
    public static AiAgentExecutionPolicy forTurn(AiChatQueuedTurn turn) {
        AiAgentExecutionPolicy policy = CURRENT.get();
        if (policy != null && !Objects.equals(policy.turn(), turn)) {
            throw new IllegalStateException("Mission callback identity changed");
        }
        return policy;
    }

    /** Proposal authority is derived only from the exact server-selected runtime. */
    public boolean proposalCapable() {
        return runtimeContractVersion == 3;
    }

    /** Filters mission declarations while leaving ordinary prompt envelopes unchanged. */
    public static boolean permits(String tool) {
        return CURRENT.get() == null || (CURRENT.get().proposalCapable()
                ? AiAgentMissionCatalog.preparationTools(CURRENT.get().scope().kind())
                : AiAgentMissionCatalog.tools(CURRENT.get().runtimeContractVersion())).contains(tool);
    }
}
