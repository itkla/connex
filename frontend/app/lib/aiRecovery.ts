const RECOVERY_POLL_INTERVAL_MS = 6_000;
const RECOVERY_DEADLINE_MS = 60_000;

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Recovers a slow AI result whose request was cut in transit (e.g. a long generation dropped by a
 * proxy idle-timeout). The server completes and caches the result regardless of whether the client
 * still holds the connection, so recovery polls a cheap cache-preferring attempt until it returns a
 * ready value or the deadline passes — sparing the reader a manual retry.
 * @param attempt cache-preferring fetch to retry
 * @param isReady whether an attempt's value is the completed result
 * @param isCancelled whether the caller has moved on (component unmounted, newer generation started)
 * @returns the recovered value, or null when nothing ready arrived before the deadline
 */
export async function recoverAiResult<T>(
    attempt: () => Promise<T>,
    isReady: (value: T) => boolean,
    isCancelled: () => boolean,
): Promise<T | null> {
    const deadline = Date.now() + RECOVERY_DEADLINE_MS;
    while (Date.now() < deadline) {
        await sleep(RECOVERY_POLL_INTERVAL_MS);
        if (isCancelled()) return null;
        try {
            const value = await attempt();
            if (isCancelled()) return null;
            if (isReady(value)) return value;
        } catch {
            continue;
        }
    }
    return null;
}
