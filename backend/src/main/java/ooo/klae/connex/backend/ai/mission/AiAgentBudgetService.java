package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;

/** Pure reservation and settlement arithmetic; the caller owns the ordered transaction. */
@Component
public final class AiAgentBudgetService {
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
        long candidateTokens = Math.min(65536, remainingTokens - 65536);
        boolean continuation = candidateSteps >= 2 && candidateTokens >= 8192
                && logicalSegmentNo < AiAgentMissionCatalog.maxSegments(run);
        return continuation
                ? new SegmentReservation(candidateSteps, candidateTokens, true, deadline)
                : new SegmentReservation(Math.min(4, remainingSteps), remainingTokens, false, deadline);
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
