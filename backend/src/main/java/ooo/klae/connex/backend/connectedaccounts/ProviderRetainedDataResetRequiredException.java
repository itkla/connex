package ooo.klae.connex.backend.connectedaccounts;

import ooo.klae.connex.backend.exceptions.ConflictException;

/** Signals that retained provider identity data must be explicitly erased before reconnecting. */
public class ProviderRetainedDataResetRequiredException extends ConflictException {

    /** Creates the stable retained-data reset conflict. */
    public ProviderRetainedDataResetRequiredException() {
        super("Retained provider data must be erased before connecting this account");
    }
}
