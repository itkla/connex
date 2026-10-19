package ooo.klae.connex.backend.tenant;

/**
 * A workspace read that performs no persistent or shared-state mutation. Its invocation stays
 * separate from the {@code Supplier} used for mutating workspace work, so static analysis cannot
 * merge progress reads with unrelated writes. Temporary thread-local identity scopes are restored
 * before returning. Implementations, including test callbacks, must not call a
 * Supplier/Runnable-based {@code TenantWorkScope} entry.
 *
 * @param <T> the read's result type
 */
@FunctionalInterface
public interface WorkspaceRead<T> {
    /**
     * Performs the read in the installed workspace placement.
     * @return the read's result
     */
    T read();
}
