package ooo.klae.connex.backend.tenant;

/**
 * A read of control-plane state that changes nothing, run through
 * {@link TenantWorkScope#unroutedRead} (#1815). It is a separate type from {@code Supplier} on
 * purpose: static analysis resolves a call through a shared functional interface by merging every
 * lambda that reaches it, so a read handed to the {@code Supplier} that mutating control-plane work
 * also uses would look as if it could reach that work's writes.
 *
 * @param <T> the read's result type
 */
@FunctionalInterface
public interface ControlPlaneRead<T> {

    /**
     * Performs the read.
     *
     * @return the read's result
     */
    T read();
}
