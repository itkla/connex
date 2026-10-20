package ooo.klae.connex.backend.exceptions;

/** Refuses a missing or inconsistent tenant workspace snapshot rather than degrading a partial operation. */
public class TenantScopeSnapshotException extends IllegalStateException {
    public TenantScopeSnapshotException(String message) {
        super(message);
    }
}
