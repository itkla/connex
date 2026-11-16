package ooo.klae.connex.backend.exceptions;

/** Refuses a control scope lookup that would borrow while a routed transaction retains a connection. */
public class TenantScopeSnapshotMissingException extends TenantScopeSnapshotException {
    public TenantScopeSnapshotMissingException(String scope) {
        super("Prepared tenant workspace scopes are unavailable for " + scope
            + "; establish the workspace scope before beginning the transaction");
    }
}
