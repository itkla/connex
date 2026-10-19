package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;

class AiAgentBudgetServiceTest {
    private final AiAgentBudgetService budget = new AiAgentBudgetService();
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");

    @Test
    void minimumCreationAllowanceFundsBothDispatchesAtTheMinimumOutputFloor() {
        var run = run(8, AiAgentBudgetService.MIN_RUN_TOKENS);
        int serializedGoalBytes = 283;
        AiAgentBudgetService.requireFeasibleCreation(serializedGoalBytes, run.getMaxTokens());
        var checkpoint = budget.reserve(run, 1, now);
        assertTrue(checkpoint.continuationCapable());
        int input = serializedGoalBytes + AiAgentBudgetService.FIXED_ENVELOPE_BYTES
                + AiAgentBudgetService.CONTEXT_RESERVE_BYTES;
        assertEquals(AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS, budget.maxOutputTokens(
                segment(checkpoint.steps(), checkpoint.tokens(), 0, 0, 0, false), input, 16384));
        var settled = budget.settle(segment(checkpoint.steps(), checkpoint.tokens(), 1, input,
                AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS, false));
        run.setConsumedSteps(settled.steps());
        run.setConsumedTokens(settled.tokens());
        var report = budget.reserve(run, 2, now);
        assertFalse(report.continuationCapable());
        assertEquals(AiAgentBudgetService.CLOSING_RESERVE_TOKENS, report.tokens());
        assertEquals(AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS, budget.maxOutputTokens(
                segment(report.steps(), report.tokens(), 0, 0, 0, false), input,
                AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS));
    }

    @Test
    void nonfinalReservationPreservesClosingAllowance() {
        for (int steps = 1; steps <= 8; steps++) {
            for (long tokens : new long[] {8192, 65536, 73727, 73728, 131072}) {
                var run = run(steps, tokens);
                var reservation = budget.reserve(run, 1, now);
                boolean continuation = steps >= 3 && tokens >= 73728;
                assertEquals(continuation, reservation.continuationCapable());
                assertEquals(continuation ? Math.min(4, steps - 1) : Math.min(4, steps), reservation.steps());
                assertEquals(continuation ? Math.min(65536, tokens - 65536) : tokens, reservation.tokens());
                if (continuation) {
                    assertTrue(steps - reservation.steps() >= 1);
                    assertTrue(tokens - reservation.tokens() >= 65536);
                }
            }
        }
        assertFalse(budget.reserve(run(8, 131072), 8, now).continuationCapable());
    }

    @Test
    void cumulativeSettlementRefundsOnlyProvenUnusedAllowance() {
        var first = segment(4, 65536, 3, 12000, 8000, false);
        var second = segment(4, 65536, 1, 3000, 1000, true);
        var completed = budget.settle(first);
        var interrupted = budget.settle(second);
        assertEquals(3, completed.steps());
        assertEquals(20000, completed.tokens());
        assertEquals(4, interrupted.steps());
        assertEquals(65536, interrupted.tokens());
        assertEquals(7, completed.steps() + interrupted.steps());
        assertEquals(85536, completed.tokens() + interrupted.tokens());
        var overrun = budget.settle(segment(2, 8192, 3, 10000, 2000, true));
        assertEquals(3, overrun.steps());
        assertEquals(12000, overrun.tokens());
    }

    @Test
    void reservationsRetainOriginalDeadlinesAndConsumedRootAllowance() {
        var run = run(8, 131072);
        run.setConsumedSteps(3);
        run.setConsumedTokens(30000);
        run.setHardDeadlineAt(LocalDateTime.ofInstant(now.plusSeconds(65), ZoneOffset.UTC));
        var reservation = budget.reserve(run, 3, now);
        assertEquals(now.plusSeconds(65), reservation.deadline());
        assertEquals(4, reservation.steps());
        assertEquals(35536, reservation.tokens());
        assertEquals(3, run.getConsumedSteps());
        assertEquals(30000, run.getConsumedTokens());
        run.setExecutionDeadlineAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(run, 3, now));
    }

    @Test
    void legacyRetainsItsEntireRemainingSingleSegmentReservation() {
        var run = run(8, 131072);
        run.setRuntimeContractVersion(1);
        var reservation = budget.reserve(run, 1, now);
        assertEquals(8, reservation.steps());
        assertEquals(131072, reservation.tokens());
        assertFalse(reservation.continuationCapable());
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(run, 2, now));
    }

    @Test
    void outputClampUsesOnlyUnusedSegmentTokensAndPreservesTheOutputFloor() {
        var segment = segment(4, 8192, 1, 1000, 500, false);
        assertEquals(4692, budget.maxOutputTokens(segment, 2000, 16384));
        assertEquals(1024, budget.maxOutputTokens(segment, 2000, 1024));
        assertEquals(AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS,
                budget.maxOutputTokens(segment, 6436, 16384));
        var refused = assertThrows(AiAssistantLoopException.class,
                () -> budget.maxOutputTokens(segment, 6437, 16384));
        assertEquals("mission_budget_exhausted", refused.terminalReason());
        segment.setUsageUncertain(true);
        assertThrows(AiAssistantLoopException.class, () -> budget.maxOutputTokens(segment, 2000, 16384));
        segment.setUsageUncertain(false);
        segment.setKnownSteps(4);
        assertThrows(AiAssistantLoopException.class, () -> budget.maxOutputTokens(segment, 2000, 16384));
    }

    @Test
    void outputClampDoesNotSubtractTheRootClosingReserveTwice() {
        var reservation = budget.reserve(run(8, 73728), 1, now);
        assertTrue(reservation.continuationCapable());
        assertEquals(8192, reservation.tokens());
        assertEquals(6192, budget.maxOutputTokens(
                segment(reservation.steps(), reservation.tokens(), 0, 0, 0, false), 2000, 16384));
    }

    @Test
    void invalidOrAlreadyReservedAllowanceAndOverflowRefuse() {
        var reserved = run(8, 131072);
        reserved.setReservedTokens(1);
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(reserved, 1, now));
        var exhausted = run(8, 131072);
        exhausted.setConsumedSteps(8);
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(exhausted, 1, now));
        assertThrows(ArithmeticException.class,
                () -> budget.settle(segment(2, 8192, 1, Long.MAX_VALUE, 1, false)));
    }

    private AiAgentRun run(int steps, long tokens) {
        var run = new AiAgentRun();
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(2);
        run.setMaxSteps(steps);
        run.setMaxTokens(tokens);
        run.setExecutionDeadlineAt(LocalDateTime.ofInstant(now.plusSeconds(900), ZoneOffset.UTC));
        run.setHardDeadlineAt(LocalDateTime.ofInstant(now.plusSeconds(900), ZoneOffset.UTC));
        return run;
    }

    private static AiAgentSegment segment(int reservedSteps, long reservedTokens, int knownSteps,
            long input, long output, boolean uncertain) {
        var segment = new AiAgentSegment();
        segment.setReservedSteps(reservedSteps);
        segment.setReservedTokens(reservedTokens);
        segment.setKnownSteps(knownSteps);
        segment.setKnownInputTokens(input);
        segment.setKnownOutputTokens(output);
        segment.setUsageUncertain(uncertain);
        return segment;
    }
}
