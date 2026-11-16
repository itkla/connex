/**
 * Groups metadata bursts while keeping messages and terminal state prompt; streamed deltas use
 * their separate path and never wait for this timer.
 */
export const ASK_CONNEX_REALTIME_REFRESH_DELAY_MS = 150;

type TimerHandle = ReturnType<typeof setTimeout>;

/** Refresh work and timer hooks for a single serialized realtime refresh queue. */
export type RealtimeRefreshCoalescerOptions = {
    run: (sessionIds: ReadonlySet<number>, signal: AbortSignal) => Promise<void>;
    delayMs: number;
    schedule?: (callback: () => void, delayMs: number) => TimerHandle;
    cancel?: (handle: TimerHandle) => void;
};

/** Batches session invalidations until disposed, leaving in-flight work to its abort signal. */
export type RealtimeRefreshCoalescer = {
    enqueue: (sessionId: number, signal: AbortSignal) => void;
    dispose: () => void;
};

/**
 * Runs each batch after its enqueues, with at most one run in flight. Enqueues received during
 * a run form a fresh batch, even for the same session. A new signal replaces pending work from
 * the previous identity; aborted batches are discarded. The caller owns refresh error reporting, and
 * a failed run still releases the queue so later invalidations can retry.
 */
export function createRealtimeRefreshCoalescer({
    run,
    delayMs,
    schedule = (callback, delay) => setTimeout(callback, delay),
    cancel = (handle) => clearTimeout(handle),
}: RealtimeRefreshCoalescerOptions): RealtimeRefreshCoalescer {
    let pendingIds = new Set<number>();
    let pendingSignal: AbortSignal | null = null;
    let timer: TimerHandle | null = null;
    let running = false;
    let disposed = false;

    function armTimer(): void {
        if (disposed || running || timer !== null || pendingIds.size === 0) return;
        timer = schedule(() => { void flush(); }, delayMs);
    }

    async function flush(): Promise<void> {
        timer = null;
        const sessionIds = pendingIds;
        const signal = pendingSignal;
        pendingIds = new Set();
        pendingSignal = null;
        if (disposed || signal === null || signal.aborted) return;
        running = true;
        try {
            await run(sessionIds, signal).catch(() => undefined);
        } finally {
            running = false;
            armTimer();
        }
    }

    return {
        enqueue: (sessionId, signal) => {
            if (disposed) return;
            if (pendingSignal !== signal) {
                pendingIds.clear();
                pendingSignal = signal;
            }
            pendingIds.add(sessionId);
            armTimer();
        },
        dispose: () => {
            disposed = true;
            if (timer !== null) {
                cancel(timer);
                timer = null;
            }
            pendingIds.clear();
            pendingSignal = null;
        },
    };
}
