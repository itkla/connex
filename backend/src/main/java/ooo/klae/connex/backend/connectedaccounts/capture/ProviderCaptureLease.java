package ooo.klae.connex.backend.connectedaccounts.capture;

/**
 * Owner-bound lease heartbeat available between bounded provider calls.
 */
@FunctionalInterface
public interface ProviderCaptureLease {
    /** Extends the active capture claim or fails when ownership was lost. */
    void renew();
}
