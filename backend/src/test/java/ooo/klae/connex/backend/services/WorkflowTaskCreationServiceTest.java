package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;

/** Pins the NOWAIT membership fence before ordinary task creation takes board and audit roots. */
class WorkflowTaskCreationServiceTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final RecordVisibilityScopeControlAccess scopes = mock(RecordVisibilityScopeControlAccess.class);
    private final WorkflowTaskCreationService service = new WorkflowTaskCreationService(workspace, tasks, scopes);
    private final RecordVisibilityScopeControlAccess.RecordVisibilityScope scope =
        new RecordVisibilityScopeControlAccess.RecordVisibilityScope(3, List.of(7));

    @BeforeEach
    void visibilityScope() {
        lenient().when(scopes.getForWorkspace(7)).thenReturn(scope);
        lenient().when(scopes.withWorkspaceScope(eq(7), eq(scope), any())).thenAnswer(call -> {
            Supplier<Task> work = call.getArgument(2);
            return work.get();
        });
    }

    @Test
    void retainsTheAssigneeBeforeDelegatingToTaskCreation() {
        Task task = task();
        when(workspace.lockActiveMemberWithoutWaiting(7, 23)).thenReturn(true);
        when(tasks.create(task)).thenReturn(task);

        assertSame(task, service.create(7, task));

        var order = inOrder(scopes, workspace, tasks);
        order.verify(scopes).getForWorkspace(7);
        order.verify(scopes).withWorkspaceScope(eq(7), eq(scope), any());
        order.verify(workspace).lockActiveMemberWithoutWaiting(7, 23);
        order.verify(tasks).create(task);
    }

    @Test
    void departureAfterAnAdvisoryReadRefusesInsteadOfCreatingForAnotherMember() {
        when(workspace.getRole(7, 23)).thenReturn("member");
        assertEquals("member", workspace.getRole(7, 23));

        WorkflowExecutionException failure = assertThrows(WorkflowExecutionException.class,
            () -> service.create(7, task()));

        assertEquals("action_target_member_unavailable", failure.code());
        assertTrue(failure.interventionRequired());
        verifyNoInteractions(tasks);
    }

    @Test
    void nowaitContentionRemainsRetryableByBothWorkflowEngines() {
        ConflictException contention = new ConflictException("Workspace membership is changing; try again");
        when(workspace.lockActiveMemberWithoutWaiting(7, 23)).thenThrow(contention);

        CannotAcquireLockException failure = assertThrows(CannotAcquireLockException.class,
            () -> service.create(7, task()));

        assertSame(contention, failure.getCause());
        WorkflowActionRetryPolicy retries = new WorkflowActionRetryPolicy(mock(WorkflowRuntimeProperties.class));
        assertTrue(retries.transientDatabaseFailure(failure));
        verifyNoInteractions(tasks);
    }

    @Test
    void missingAssigneeRefusesBeforeAnyLockOrTaskWrite() {
        assertThrows(BadRequestException.class, () -> service.create(7, new Task()));
        verifyNoInteractions(workspace, tasks, scopes);
    }

    private static Task task() {
        Task task = new Task();
        User assignee = new User();
        assignee.setId(23);
        task.setAssignedTo(assignee);
        return task;
    }
}
