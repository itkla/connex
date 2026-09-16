package ooo.klae.connex.backend.sso;

import java.io.IOException;

/** Identifies deadline and interruption failures independently of destination policy refusals. */
public final class SsoTransportException extends IOException {

    /** The bounded transport failure category, suitable for address-free operator logs. */
    public enum Reason {
        TIMEOUT, INTERRUPTED
    }

    private final Reason reason;

    /** Creates an address-free transport failure while preserving its cause. */
    public SsoTransportException(Reason reason, Throwable cause) {
        super(reason == Reason.TIMEOUT ? "OIDC request exceeded its deadline" : "OIDC request interrupted", cause);
        this.reason = reason;
    }

    /** Returns the transport failure category. */
    public Reason reason() {
        return reason;
    }
}
