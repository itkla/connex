package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/** Revalidated principal and attribution used for one canonical node transaction. */
public record WorkflowExecutionPrincipal(
    User principal,
    String role,
    int actorUserId,
    int attributionUserId
) { }
