package ooo.klae.connex.backend.password;

/**
 * Internal fixed-detail signal that a screening source could not answer safely.
 */
public class BreachedPasswordSourceUnavailableException extends RuntimeException {
    private final BreachedPasswordUnavailableReason reason;

    public BreachedPasswordSourceUnavailableException(BreachedPasswordUnavailableReason reason) {
        super("Breached-password source unavailable");
        this.reason = reason;
    }

    public BreachedPasswordUnavailableReason getReason() {
        return reason;
    }
}
