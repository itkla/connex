package ooo.klae.connex.backend.services;

/**
 * A record-visibility operation that changes no state, run through
 * {@link RecordVisibilityScopeControlAccess#withWorkspaceScopeRead}. It is a separate type from
 * {@code Supplier} on purpose: static analysis merges callbacks reaching a shared functional
 * invocation, so sharing that invocation with mutations would make a read appear to reach writes.
 * Read callbacks must perform no persistence, audit appends, locks, or event publication.
 *
 * @param <T> the read's result type
 */
@FunctionalInterface
public interface RecordVisibilityRead<T> {
    /**
     * Performs the read.
     *
     * @return the read's result
     */
    T read();
}
