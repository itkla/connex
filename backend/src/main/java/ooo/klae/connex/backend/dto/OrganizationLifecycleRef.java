package ooo.klae.connex.backend.dto;

/** Immutable control-plane organization identity used by trusted lifecycle operations. */
public record OrganizationLifecycleRef(
        int id,
        String name,
        String slug,
        String lifecycleState) {

    /** Whether ordinary work may still resolve this organization. */
    public boolean active() {
        return "active".equals(lifecycleState);
    }
}
