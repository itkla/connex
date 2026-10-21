package ooo.klae.connex.backend.services;

/**
 * A lock-free attachment library read. This callback never shares Supplier's invocation with writes,
 * so GET handlers cannot inherit mutation paths through static-analysis callback merging.
 *
 * @param <T> read result
 */
@FunctionalInterface
public interface AttachmentQueryRead<T> {
    /** Performs tenant discovery, control fact reads and tenant result reads without effects. */
    T read();
}
