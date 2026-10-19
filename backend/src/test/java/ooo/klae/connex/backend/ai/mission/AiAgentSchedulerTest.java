package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAgentSchedulerTest {
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final AiAgentPrivacyInitializer initializer = mock(AiAgentPrivacyInitializer.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiAgentRunPersistenceService persistence = mock(AiAgentRunPersistenceService.class);
    private final AiAgentRealtimePublisher publisher = mock(AiAgentRealtimePublisher.class);
    private final JobRunRecorder jobs = mock(JobRunRecorder.class);
    private final AiAgentScheduler scheduler = new AiAgentScheduler(mock(WorkspaceMapper.class), scope,
            runs, mock(AiAgentSegmentRunner.class), initializer,
            new AiProperties(), persistence, publisher, jobs);

    @Test
    void unavailableInitializationCannotQueueWork() {
        try {
            scheduler.sweep();
            verifyNoInteractions(scope);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void publishesFreshStateAfterExpiryCommitsEvenWhenMissionsAreDisabled() throws Exception {
        AiAgentRun expired = new AiAgentRun();
        expired.setId(11);
        expired.setStatus("paused");
        AiAgentRun settled = new AiAgentRun();
        settled.setId(12);
        AiAgentRun removed = new AiAgentRun();
        removed.setId(13);
        AiAgentRun current = new AiAgentRun();
        current.setId(11);
        current.setStatus("failed");
        current.setReasonCode("expired");
        current.setStateVersion(2);
        CountDownLatch completed = new CountDownLatch(1);
        when(initializer.isReady()).thenReturn(true);
        when(scope.unroutedRead(any())).thenReturn(List.of(7));
        when(scope.inWorkspace(eq(7), ArgumentMatchers.<Supplier<Object>>any())).thenAnswer(invocation -> {
            Supplier<Object> work = invocation.getArgument(1);
            try {
                return work.get();
            } finally {
                completed.countDown();
            }
        });
        when(runs.expiredRuns(7, 25)).thenReturn(List.of(expired, settled, removed));
        when(persistence.expire(expired)).thenReturn(true);
        when(persistence.expire(removed)).thenReturn(true);
        when(runs.getRun(7, 11)).thenReturn(current);

        try {
            scheduler.sweep();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            var order = inOrder(persistence, runs, publisher);
            order.verify(runs).expiredRuns(7, 25);
            order.verify(persistence).expire(expired);
            order.verify(runs).getRun(7, 11);
            order.verify(publisher).publish(current, "mission_state");
            order.verify(persistence).expire(settled);
            order.verify(persistence).expire(removed);
            order.verify(runs).getRun(7, 13);
            order.verifyNoMoreInteractions();
            verify(runs, never()).getRun(7, 12);
            verify(runs, never()).dueRuns(7, 1);
            verify(jobs).record(eq(JobRunRecorder.AI_AGENT_SCHEDULER), eq(7),
                    eq(JobRunRecorder.JobRunStatus.SUCCEEDED), any());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void busyWorkerDoesNotBlockMaintenanceOrQueueAnotherPass() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(initializer.isReady()).thenReturn(true);
        when(scope.unroutedRead(any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                release.await();
                return List.of();
            } finally {
                stopped.countDown();
            }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), scheduler::sweep);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(5), scheduler::sweep);
            verify(scope, times(1)).unroutedRead(any());
        } finally {
            scheduler.shutdown();
            release.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        }
    }
}
