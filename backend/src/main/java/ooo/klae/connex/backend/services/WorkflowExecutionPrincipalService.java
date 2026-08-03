package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Revalidates the immutable version's configured actor immediately before each node. */
@Service
@RequiredArgsConstructor
public class WorkflowExecutionPrincipalService {

    private final WorkspaceService workspaceService;
    private final UserMapper userMapper;
    private final SystemActor systemActor;

    public WorkflowExecutionPrincipal resolve(int workspaceId, WorkflowVersion version) {
        if ("system".equals(version.getExecutionMode())) {
            Integer attributionId = version.getCreatedById();
            if (attributionId == null || workspaceService.getRole(workspaceId, attributionId) == null) {
                throw new WorkflowExecutionException(
                    "actor_unavailable",
                    "The configured workflow attribution member is no longer active.",
                    true);
            }
            User actor = systemActor.user();
            return new WorkflowExecutionPrincipal(
                actor, "system", actor.getId(), attributionId);
        }
        Integer actorId = version.getRunAsUserId();
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
        return new WorkflowExecutionPrincipal(actor, role, actorId, actorId);
    }
}
