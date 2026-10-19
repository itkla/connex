package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;

class AiAgentStartupRecoveryServiceTest {
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiAgentRunPersistenceService persistence = mock(AiAgentRunPersistenceService.class);
    private final AiRunLeaseMapper leases = mock(AiRunLeaseMapper.class);
    private final AiAgentStartupRecoveryService recovery = new AiAgentStartupRecoveryService(runs, persistence, leases,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void recoveryPassesObservedEpochToConditionalTakeoverInsteadOfUnconditionalInterruption() {
        AiAgentRun run = running();
        when(runs.getRun(7, 31)).thenReturn(run);
        when(leases.findExpiredLeases(7, List.of("agent_run"), 50)).thenReturn(List.of(expired()));

        recovery.reconcile(7);

        verify(persistence).settleOrphan(run, new AiRunLeaseKey(7, AiRunLeaseSubject.AGENT_RUN, 31), 4);
        verify(persistence, never()).interrupt(any(), any());
    }

    @Test
    void heartbeatWinningTakeoverRacePreventsReadinessFromClaimingSuccessfulSettlement() {
        AiAgentRun run = running();
        when(runs.getRun(7, 31)).thenReturn(run);
        when(leases.findExpiredLeases(7, List.of("agent_run"), 50)).thenReturn(List.of(expired()));
        when(persistence.settleOrphan(run, new AiRunLeaseKey(7, AiRunLeaseSubject.AGENT_RUN, 31), 4))
                .thenThrow(new ConflictException("mission_state_conflict"));

        assertThrows(ConflictException.class, () -> recovery.reconcile(7));

        verify(persistence, never()).interrupt(any(), any());
    }

    @Test
    void missingLeaseDoesNotInventOwnershipAndKeepsReadinessClosed() {
        when(runs.orphanedRuns(7, 1)).thenReturn(List.of(running()));

        assertThrows(IllegalStateException.class, () -> recovery.reconcile(7));

        verifyNoInteractions(persistence);
    }

    @Test
    void inconsistentAttemptLinksRefuseBeforeLeaseDiscovery() {
        when(runs.countInconsistentActiveRuns(7)).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> recovery.reconcile(7));

        verifyNoInteractions(leases, persistence);
        verify(runs, never()).orphanedRuns(anyInt(), anyInt());
    }

    @Test
    void malformedRunnableContinuationKeepsReadinessClosedWithoutChangingPayload() {
        var run = running();
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(2);
        run.setStatus("queued");
        when(runs.dueRuns(7, 25)).thenReturn(List.of(run));
        assertThrows(IllegalArgumentException.class, () -> recovery.reconcile(7));
        verifyNoInteractions(persistence);
    }

    private static AiAgentRun running() {
        AiAgentRun run = new AiAgentRun();
        run.setId(31);
        run.setWorkspaceId(7);
        run.setStatus("running");
        return run;
    }

    private static AiRunLeaseRow expired() {
        AiRunLeaseRow row = new AiRunLeaseRow();
        row.setWorkspaceId(7);
        row.setSubjectKind("agent_run");
        row.setSubjectId(31);
        row.setEpoch(4);
        return row;
    }
}
