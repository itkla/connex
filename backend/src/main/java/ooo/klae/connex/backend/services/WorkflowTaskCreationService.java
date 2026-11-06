package ooo.klae.connex.backend.services;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;

/** Retains a workflow task's assignee without waiting behind membership-first audited writes. */
@Service
@RequiredArgsConstructor
public class WorkflowTaskCreationService {
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final RecordVisibilityScopeControlAccess visibilityScopes;

    /** Joins the effect transaction and retains the exact assignee before TaskService re-enters it. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Task create(int workspaceId, Task task) {
        if (task.getAssignedTo() == null || task.getAssignedTo().getId() < 1) {
            throw new BadRequestException("Task assignee is required");
        }
        var scope = visibilityScopes.getForWorkspace(workspaceId);
        return visibilityScopes.withWorkspaceScope(workspaceId, scope, () -> createWithRetainedAssignee(workspaceId, task));
    }

    private Task createWithRetainedAssignee(int workspaceId, Task task) {
        boolean active;
        try {
            active = workspaceService.lockActiveMemberWithoutWaiting(workspaceId, task.getAssignedTo().getId());
        } catch (ConflictException contention) {
            throw new CannotAcquireLockException("The selected follow-up member is busy; try again.", contention);
        }
        if (!active) {
            throw new WorkflowExecutionException(
                WorkflowDiagnosticCode.ACTION_TARGET_MEMBER_UNAVAILABLE.value(),
                "The selected follow-up member is no longer available.", true);
        }
        return taskService.create(task);
    }
}
