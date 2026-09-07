package ooo.klae.connex.backend.services;

import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Revalidates the immutable version's configured actor immediately before each node. */
@Service
@RequiredArgsConstructor
public class WorkflowExecutionPrincipalService {

    private final WorkspaceService workspaceService;
    private final UserMapper userMapper;
    private final SystemActor systemActor;

    public WorkflowExecutionPrincipal resolve(int workspaceId, WorkflowVersion version) {
        return resolve(
            workspaceId,
            version.getExecutionMode(),
            version.getRunAsUserId(),
            version.getCreatedById(), Set.of());
    }

    /** Resolves an actor after locking the authorization hierarchy ahead of tenant records. */
    public WorkflowExecutionPrincipal resolveLocked(int workspaceId, WorkflowVersion version) {
        Integer authorizationMemberId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById() : version.getRunAsUserId();
        if (authorizationMemberId == null) {
            throw new WorkflowExecutionException(
                "actor_unavailable",
                "The configured workflow actor is unavailable.",
                true);
        }
        Set<Permission> permissions = "system".equals(version.getExecutionMode())
            ? systemActor.permissions()
            : workspaceService.lockedPermissionsFor(workspaceId, authorizationMemberId);
        if ("system".equals(version.getExecutionMode())) {
            workspaceService.lockedMemberPermissionsFor(workspaceId, authorizationMemberId);
        }
        return resolve(
            workspaceId,
            version.getExecutionMode(),
            version.getRunAsUserId(),
            version.getCreatedById(),
            permissions);
    }

    /** Revalidates the configured actor of one saved workflow draft without version persistence. */
    public WorkflowExecutionPrincipal resolveDraft(
            int workspaceId,
            String executionMode,
            Integer runAsUserId,
            Integer createdById) {
        return resolve(workspaceId, executionMode, runAsUserId, createdById, Set.of());
    }

    private WorkflowExecutionPrincipal resolve(
            int workspaceId,
            String executionMode,
            Integer runAsUserId,
            Integer createdById,
            Set<Permission> lockedPermissions) {
        if ("system".equals(executionMode)) {
            Integer attributionId = createdById;
            if (attributionId == null || workspaceService.getRole(workspaceId, attributionId) == null) {
                throw new WorkflowExecutionException(
                    "actor_unavailable",
                    "The configured workflow attribution member is no longer active.",
                    true);
            }
            User actor = systemActor.user();
            return new WorkflowExecutionPrincipal(
                actor, "system", actor.getId(), attributionId, lockedPermissions);
        }
        Integer actorId = runAsUserId;
        if (actorId == null) {
            throw new WorkflowExecutionException(
                "actor_unavailable",
                "The configured workflow actor is unavailable.",
                true);
        }
        String role = workspaceService.getRole(workspaceId, actorId);
        User actor = role == null ? null : userMapper.getUserById(actorId);
        if (actor == null) {
            throw new WorkflowExecutionException(
                "actor_unavailable",
                "The configured workflow actor is no longer an active member.",
                true);
        }
        return new WorkflowExecutionPrincipal(
            actor, role, actorId, actorId, lockedPermissions);
    }
}
