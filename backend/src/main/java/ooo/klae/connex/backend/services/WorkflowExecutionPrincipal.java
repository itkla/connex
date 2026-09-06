package ooo.klae.connex.backend.services;

import java.util.Set;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.tenant.Permission;

/** Revalidated principal and attribution used for one canonical node transaction. */
public record WorkflowExecutionPrincipal(
    User principal,
    String role,
    int actorUserId,
    int attributionUserId,
    Set<Permission> lockedPermissions
) {

    public WorkflowExecutionPrincipal {
        lockedPermissions = Set.copyOf(lockedPermissions);
    }

    public WorkflowExecutionPrincipal(
            User principal, String role, int actorUserId, int attributionUserId) {
        this(principal, role, actorUserId, attributionUserId, Set.of());
    }
}
