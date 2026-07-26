package ooo.klae.connex.backend.dto;

/** Immutable control-plane workspace identity used by trusted lifecycle operations. */
public record WorkspaceLifecycleRef(
        int id,
        int orgId,
        String name,
        String slug,
        String lifecycleState) {

    /** Whether ordinary work may still resolve this workspace. */
    public boolean active() {
        return "active".equals(lifecycleState);
    }
}
