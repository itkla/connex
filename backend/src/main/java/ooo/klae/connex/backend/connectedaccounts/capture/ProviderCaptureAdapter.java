package ooo.klae.connex.backend.connectedaccounts.capture;

/**
 * Provider-specific read-only mail/calendar delta adapter.
 */
public interface ProviderCaptureAdapter {
    /** Provider id served by this adapter. */
    String provider();

    /** Fetches one bounded page without holding a database transaction. */
    ProviderCapturePage fetch(ProviderCaptureRequest request);
}
