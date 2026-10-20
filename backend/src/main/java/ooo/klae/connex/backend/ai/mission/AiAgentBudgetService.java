package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.exceptions.AiAgentBudgetInfeasibleException;

/** Pure reservation and settlement arithmetic; the caller owns the ordered transaction. */
@Component
public final class AiAgentBudgetService {
    /** Funds a checkpoint dispatch and the independently retained closing-report allowance. */
    public static final long MIN_RUN_TOKENS = 81920;
    /** Includes JSON quoting and escaping; all 16,000-character Japanese goals fit. */
    static final int MAX_SERIALIZED_GOAL_BYTES = 48002;
    /** Largest runtime-2 fixed envelope: JSON fallback with tagged reasoning. */
    static final int FIXED_ENVELOPE_BYTES = 11749;
    /** Funds serialized message framing, scope instructions and reconstructed context. */
    static final int CONTEXT_RESERVE_BYTES = 4096;
    /** Retained outside a continuation segment for the later report dispatch. */
    static final long CLOSING_RESERVE_TOKENS = 65536;
    /** Leaves room for a complete final-answer or checkpoint envelope with concise prose. */
    static final int MIN_DISPATCH_OUTPUT_TOKENS = 256;

    /**
     * Refuses new requests whose serialized goal cannot fund a checkpoint and a closing report.
     * Input is charged as UTF-8 JSON bytes, without a tokenizer or bytes-to-token discount.
     * Existing requests replay without adopting this new admission contract.
     */
    public static void requireFeasibleCreation(int serializedGoalBytes, long maxTokens) {
        long dispatch = (long) serializedGoalBytes + FIXED_ENVELOPE_BYTES + CONTEXT_RESERVE_BYTES
                + MIN_DISPATCH_OUTPUT_TOKENS;
        if (serializedGoalBytes > MAX_SERIALIZED_GOAL_BYTES || maxTokens < MIN_RUN_TOKENS
                || dispatch > CLOSING_RESERVE_TOKENS || dispatch + CLOSING_RESERVE_TOKENS > maxTokens) {
            throw new AiAgentBudgetInfeasibleException();
        }
    }

    /** Immutable attempt allowance and original-deadline-constrained closing mode. */
    public record SegmentReservation(int steps, long tokens, boolean continuationCapable, Instant deadline) {
    }

    /** Exact egress reservation carried through one provider callback, including repairs. */
    public record AiAgentDispatchReservation(int runId, int segmentId, long executionGeneration,
            int dispatchOrdinal, long tokenCeiling) {
        public AiAgentDispatchReservation {
            AiAgentContinuationCodec.require(runId > 0 && segmentId > 0 && executionGeneration > 0
                    && dispatchOrdinal > 0 && tokenCeiling > 0);
        }
    }

    /** Proven usage charged once while clearing the root's attempt reservation. */
    public record Settlement(int steps, long tokens) {
    }

    /** Reserves bounded work while preserving one decision and 65,536 closing tokens. */
    public SegmentReservation reserve(AiAgentRun run, int logicalSegmentNo, Instant now) {
        AiAgentMissionCatalog.requireSupported(run);
        AiAgentContinuationCodec.require(now != null && run.getReservedSteps() == 0 && run.getReservedTokens() == 0
                && logicalSegmentNo >= 1 && logicalSegmentNo <= AiAgentMissionCatalog.maxSegments(run)
                && run.getConsumedSteps() >= 0 && run.getConsumedTokens() >= 0
                && run.getMaxSteps() > 0 && run.getMaxSteps() <= 8
                && run.getMaxTokens() > 0 && run.getMaxTokens() <= 131072
                && run.getExecutionDeadlineAt() != null && run.getHardDeadlineAt() != null);
        int remainingSteps = run.getMaxSteps() - run.getConsumedSteps();
        long remainingTokens = run.getMaxTokens() - run.getConsumedTokens();
        AiAgentContinuationCodec.require(remainingSteps > 0 && remainingTokens > 0);
        Instant deadline = now.plusSeconds(180);
        Instant executionDeadline = run.getExecutionDeadlineAt().toInstant(ZoneOffset.UTC);
        Instant hardDeadline = run.getHardDeadlineAt().toInstant(ZoneOffset.UTC);
        if (executionDeadline.isBefore(deadline)) {
            deadline = executionDeadline;
        }
        if (hardDeadline.isBefore(deadline)) {
            deadline = hardDeadline;
        }
        AiAgentContinuationCodec.require(deadline.isAfter(now));
        if (run.getRuntimeContractVersion() == 1) {
            return new SegmentReservation(remainingSteps, remainingTokens, false, deadline);
        }
        int candidateSteps = Math.min(4, remainingSteps - 1);
        long candidateTokens = Math.min(CLOSING_RESERVE_TOKENS, remainingTokens - CLOSING_RESERVE_TOKENS);
        boolean continuation = candidateSteps >= 2 && candidateTokens >= 8192
                && logicalSegmentNo < AiAgentMissionCatalog.maxSegments(run);
        return continuation
                ? new SegmentReservation(candidateSteps, candidateTokens, true, deadline)
                : new SegmentReservation(Math.min(4, remainingSteps), remainingTokens, false, deadline);
    }

    /**
     * Bounds output by the unused attempt allowance after the serialized input estimate.
     * Closing capacity for a later segment is already excluded by {@link #reserve}.
     */
    public int maxOutputTokens(AiAgentSegment segment, int promptTokens, int requestedOutputTokens) {
        AiAgentContinuationCodec.require(promptTokens >= 0 && requestedOutputTokens > 0);
        long known = Math.addExact(segment.getKnownInputTokens(), segment.getKnownOutputTokens());
        long available = segment.getReservedTokens() - known - promptTokens;
        long output = Math.min(requestedOutputTokens, available);
        if (segment.isUsageUncertain() || segment.getKnownSteps() >= segment.getReservedSteps()
                || output < MIN_DISPATCH_OUTPUT_TOKENS) {
            throw new AiAssistantLoopException("mission_budget_exhausted", "mission_budget_exhausted");
        }
        return Math.toIntExact(output);
    }

    /** Charges exact known usage, or at least the full reservation after uncertain egress. */
    public Settlement settle(AiAgentSegment segment) {
        AiAgentContinuationCodec.require(segment != null && segment.getReservedSteps() > 0
                && segment.getReservedTokens() > 0 && segment.getKnownSteps() >= 0
                && segment.getKnownInputTokens() >= 0 && segment.getKnownOutputTokens() >= 0);
        long observedTokens = Math.addExact(segment.getKnownInputTokens(), segment.getKnownOutputTokens());
        return new Settlement(segment.isUsageUncertain()
                ? Math.max(segment.getReservedSteps(), segment.getKnownSteps()) : segment.getKnownSteps(),
                segment.isUsageUncertain() ? Math.max(segment.getReservedTokens(), observedTokens) : observedTokens);
    }
}
