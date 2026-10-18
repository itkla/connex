package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.ObjectDeletionTask;
import ooo.klae.connex.backend.storage.ObjectDeletionTransactionExecutor;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class ObjectDeletionFixtureDrainTest {
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final LocalDateTime CUTOFF = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        .minusMinutes(10);

    @Mock private ObjectDeletionQueueMapper deletionQueueMapper;
    @Mock private UserObjectDeletionQueueMapper userDeletionQueueMapper;
    @Mock private ObjectDeletionTransactionExecutor deletionTransactionExecutor;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private WorkspaceMapper workspaceMapper;

    private final AbstractServiceTest fixture = new AbstractServiceTest() {};

    @BeforeEach
    void setUp() {
        fixture.workspaceMapper = workspaceMapper;
        ReflectionTestUtils.setField(fixture, "fixtureDeletionQueueMapper", deletionQueueMapper);
        ReflectionTestUtils.setField(fixture, "fixtureUserDeletionQueueMapper", userDeletionQueueMapper);
        ReflectionTestUtils.setField(fixture, "fixtureDeletionTransactionExecutor", deletionTransactionExecutor);
        ReflectionTestUtils.setField(fixture, "fixtureTenantWorkScope", tenantWorkScope);
        ReflectionTestUtils.setField(fixture, "fixtureClock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void hookContinuesAfterATaskFailureWithoutFailingTheUnrelatedTest() {
        runWorkspaceWork();
        runUnroutedWork();
        Workspace workspace = new Workspace();
        workspace.setId(7);
        when(workspaceMapper.getDefaultWorkspace()).thenReturn(workspace);
        when(tenantWorkScope.unroutedRead(any())).thenAnswer(invocation ->
            invocation.<ControlPlaneRead<?>>getArgument(0).read());
        List<ObjectDeletionTask> tasks = tasks(7);
        when(deletionQueueMapper.findDue(7, CUTOFF, 10)).thenReturn(tasks);
        doThrow(new IllegalStateException("quota lock unavailable"))
            .when(deletionTransactionExecutor).retryTenant(tasks.getFirst(), CUTOFF);

        assertDoesNotThrow(() -> fixture.drainLeakedObjectDeletions());

        verify(deletionTransactionExecutor).retryTenant(tasks.get(1), CUTOFF);
    }

    @Test
    void directHelperPropagatesATaskFailureInTheSelectedWorkspace() {
        runWorkspaceWork();
        LocalDateTime cutoff = CUTOFF.plusDays(2);
        List<ObjectDeletionTask> tasks = tasks(9);
        when(deletionQueueMapper.findDue(9, cutoff, 10)).thenReturn(tasks);
        IllegalStateException failure = new IllegalStateException("quota lock unavailable");
        doThrow(failure).when(deletionTransactionExecutor).retryTenant(tasks.getFirst(), cutoff);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> fixture.drainLeakedObjectDeletions(9, cutoff)));

        verify(deletionTransactionExecutor, never()).retryTenant(tasks.get(1), cutoff);
    }

    @Test
    void hookContinuesAfterAUserTaskFailureWhenDefaultWorkspaceIsAbsent() {
        runUnroutedWork();
        List<ObjectDeletionTask> tasks = userTasks();
        when(userDeletionQueueMapper.findDue(CUTOFF, 10)).thenReturn(tasks);
        doThrow(new IllegalStateException("user deletion unavailable"))
            .when(deletionTransactionExecutor).retryUser(tasks.getFirst(), CUTOFF);

        assertDoesNotThrow(() -> fixture.drainLeakedObjectDeletions());

        verify(deletionTransactionExecutor).retryUser(tasks.get(1), CUTOFF);
        verify(deletionQueueMapper, never()).findDue(anyInt(), any(), anyInt());
    }

    @Test
    void directUserHelperPropagatesATaskFailure() {
        runUnroutedWork();
        LocalDateTime cutoff = CUTOFF.plusDays(2);
        List<ObjectDeletionTask> tasks = userTasks();
        when(userDeletionQueueMapper.findDue(cutoff, 10)).thenReturn(tasks);
        IllegalStateException failure = new IllegalStateException("user deletion unavailable");
        doThrow(failure).when(deletionTransactionExecutor).retryUser(tasks.getFirst(), cutoff);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> fixture.drainLeakedUserObjectDeletions(cutoff)));

        verify(deletionTransactionExecutor, never()).retryUser(tasks.get(1), cutoff);
    }

    private void runWorkspaceWork() {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(tenantWorkScope).inWorkspace(anyInt(), any(Runnable.class));
    }

    private void runUnroutedWork() {
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation ->
            invocation.<Supplier<?>>getArgument(0).get());
    }

    private static List<ObjectDeletionTask> tasks(int workspaceId) {
        return List.of(
            new ObjectDeletionTask(1, workspaceId, "workspaces/" + workspaceId + "/attachments/first.pdf", 1, 1),
            new ObjectDeletionTask(2, workspaceId, "workspaces/" + workspaceId + "/attachments/second.pdf", 1, 1));
    }

    private static List<ObjectDeletionTask> userTasks() {
        return List.of(
            new ObjectDeletionTask(1, 0, "users/7/profile-images/first.png", 1, 1),
            new ObjectDeletionTask(2, 0, "users/7/profile-images/second.png", 1, 1));
    }
}
