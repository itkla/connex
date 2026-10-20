type ServerClock = {
    timestamp: number;
    receivedAt: number;
};

let clock: ServerClock | null = null;
const listeners = new Set<() => void>();

/** Calibrates review deadlines from an uncached API response, independent of the device clock. */
export function observeProposalReviewClock(headers: Headers): void {
    const timestamp = Date.parse(headers.get('Date') ?? '');
    if (!Number.isFinite(timestamp)) return;
    clock = { timestamp, receivedAt: performance.now() };
    for (const listener of listeners) listener();
}

/** Returns the last server-time sample; its identity changes only on calibration. */
export function proposalReviewClockSnapshot(): ServerClock | null {
    return clock;
}

/** Watches server-time calibration so mounted deadline timers can be rescheduled. */
export function subscribeProposalReviewClock(listener: () => void): () => void {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
}

/** Advances server time monotonically; without a sample, actions remain disarmed. */
export function proposalReviewNow(): number {
    return clock === null ? NaN : clock.timestamp + performance.now() - clock.receivedAt;
}
