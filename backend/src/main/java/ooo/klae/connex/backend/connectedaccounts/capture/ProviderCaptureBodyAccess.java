package ooo.klae.connex.backend.connectedaccounts.capture;

/**
 * Metadata-level authorization gate for an optional provider body read.
 */
@FunctionalInterface
public interface ProviderCaptureBodyAccess {
    /** Whether the already-bounded metadata item may have its body retrieved. */
    boolean allows(ProviderCaptureItem item);
}
