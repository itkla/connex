package ooo.klae.connex.backend.publicapi;

/**
 * A credential row that account erasure has already deleted and whose retained audit has not been
 * emitted yet. The erasure appends into one audit-integrity head per distinct scope, so it collects
 * these and emits them in its own canonical scope order instead of auditing inside the delete loop.
 * Only {@link ApiCredentialLifecycleService} produces instances, so an emission can never exist
 * without the deletion that earned it.
 */
public interface PendingCredentialAudit {

    /** Workspace whose audit-integrity head this emission locks. */
    int workspaceId();

    /** Organization owning {@link #workspaceId()}. */
    int organizationId();

    /** Appends the retained account-erasure evidence for the deleted credential. */
    void emit();
}
