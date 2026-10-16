package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAgentSchedulerTest {
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final AiAgentPrivacyInitializer initializer = mock(AiAgentPrivacyInitializer.class);
    private final AiAgentScheduler scheduler = new AiAgentScheduler(mock(WorkspaceMapper.class), scope,
            mock(AiAgentRunMapper.class), mock(AiAgentSegmentRunner.class), initializer,
            new AiProperties(), mock(AiAgentRunPersistenceService.class), mock(JobRunRecorder.class));

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
