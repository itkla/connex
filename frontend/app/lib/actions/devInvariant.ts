/**
 * Enforces an invariant the action registry depends on (unique ids, non-conflicting shortcuts).
 *
 * In development the failure is loud — it throws — because the frontend has no unit-test runner, so a
 * swallowed warning would go unnoticed. In production it degrades to a `console.error` so a single bad
 * registration cannot blank the authenticated shell.
 *
 * @param condition - the invariant expected to hold
 * @param message - describes the violation when the invariant fails
 * @throws when `condition` is false outside production
 */
export function devInvariant(condition: boolean, message: string): void {
    if (condition) return;
    if (process.env.NODE_ENV === "production") {
        console.error(`[actions] ${message}`);
        return;
    }
    throw new Error(`[actions] ${message}`);
}
