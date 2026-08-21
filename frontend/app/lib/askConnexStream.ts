import type { AiChatDeltaFrame, AiChatRealtimeFrame } from '@/app/lib/types';
import type { AskConnexTurnState } from '@/app/lib/askConnex';

/** Upper bound on consecutive REST re-hydrations for one streaming turn. */
export const ASK_CONNEX_STREAM_HYDRATION_LIMIT = 8;

/**
 * Client reassembly state for one streaming assistant turn. `text` is the applied prefix of the
 * turn's streamed answer; delta frames carry their character offset in `seq`, so a frame whose
 * offset exceeds the applied length signals a gap that must be repaired from the persisted
 * partial. Frames received while a gap is being repaired wait in `pending`.
 */
export type AskConnexStreamState = {
    turnId: number;
    text: string;
    pending: readonly AiChatDeltaFrame[];
    hydrating: boolean;
    hydrations: number;
};

/** Result of advancing a stream: the next state plus whether a REST re-hydration must start. */
export type AskConnexStreamTransition = {
    state: AskConnexStreamState;
    hydrate: boolean;
};

/** Creates the empty reassembly state for one turn's live answer stream. */
export function createAskConnexStream(turnId: number): AskConnexStreamState {
    return { turnId, text: '', pending: [], hydrating: false, hydrations: 0 };
}

function appendDelta(text: string, frame: AiChatDeltaFrame): string | null {
    if (frame.seq > text.length) return null;
    const unapplied = frame.seq + frame.text.length - text.length;
    if (unapplied <= 0) return text;
    return text + frame.text.slice(frame.text.length - unapplied);
}

function withPendingFrame(
    pending: readonly AiChatDeltaFrame[],
    frame: AiChatDeltaFrame,
): readonly AiChatDeltaFrame[] {
    if (pending.some((buffered) => buffered.seq === frame.seq)) return pending;
    return [...pending, frame].toSorted((left, right) => left.seq - right.seq);
}

function drainPending(state: AskConnexStreamState): AskConnexStreamState {
    let text = state.text;
    const remaining: AiChatDeltaFrame[] = [];
    for (const frame of state.pending) {
        const applied = appendDelta(text, frame);
        if (applied === null) {
            remaining.push(frame);
        } else {
            text = applied;
        }
    }
    return { ...state, text, pending: remaining };
}

function mergePartial(
    state: AskConnexStreamState,
    partial: string,
): AskConnexStreamState {
    return drainPending({
        ...state,
        text: partial.length > state.text.length ? partial : state.text,
    });
}

/** Returns whether a durable reset invalidation starts a fresh stream for this turn. */
export function shouldResetAskConnexStream(
    state: AskConnexStreamState | null,
    frame: AiChatRealtimeFrame,
): boolean {
    return state?.turnId === frame.turnId
        && frame.kind === 'reset'
        && frame.seq === 0
        && frame.status === 'running';
}

/**
 * Applies one live delta frame by character offset. Fully overlapped frames are dropped, a frame
 * straddling the applied length contributes only its unapplied suffix, and a frame beyond the
 * applied length is buffered while requesting one bounded REST re-hydration of the persisted
 * partial. Frames for another turn are ignored.
 */
export function applyAskConnexStreamDelta(
    state: AskConnexStreamState,
    frame: AiChatDeltaFrame,
): AskConnexStreamTransition {
    if (frame.turnId !== state.turnId) return { state, hydrate: false };
    if (state.hydrating) {
        return { state: { ...state, pending: withPendingFrame(state.pending, frame) }, hydrate: false };
    }
    const applied = appendDelta(state.text, frame);
    if (applied !== null) {
        const advanced = state.pending.length === 0
            ? { ...state, text: applied }
            : drainPending({ ...state, text: applied });
        return { state: advanced, hydrate: false };
    }
    const gapped: AskConnexStreamState = {
        ...state,
        pending: withPendingFrame(state.pending, frame),
    };
    if (gapped.hydrations >= ASK_CONNEX_STREAM_HYDRATION_LIMIT) {
        return { state: gapped, hydrate: false };
    }
    return {
        state: { ...gapped, hydrating: true, hydrations: gapped.hydrations + 1 },
        hydrate: true,
    };
}

/**
 * Merges the REST-hydrated persisted partial and replays buffered frames. The partial is adopted
 * only when it extends the applied text — live application may already be ahead of persistence —
 * and buffered frames dedupe against it by character offset. A frame still beyond the merged
 * length keeps the gap open and requests another bounded re-hydration.
 */
export function settleAskConnexStreamHydration(
    state: AskConnexStreamState,
    partial: string,
): AskConnexStreamTransition {
    const merged = mergePartial({ ...state, hydrating: false }, partial);
    if (merged.pending.length === 0 || merged.hydrations >= ASK_CONNEX_STREAM_HYDRATION_LIMIT) {
        return { state: merged, hydrate: false };
    }
    return {
        state: { ...merged, hydrating: true, hydrations: merged.hydrations + 1 },
        hydrate: true,
    };
}

/**
 * Absorbs a durable partial observed outside the active hydration request. An existing hydration
 * remains its turn's sole retry owner, while a caller without one may start the bounded repair.
 */
export function absorbAskConnexStreamPartial(
    state: AskConnexStreamState,
    partial: string,
): AskConnexStreamTransition {
    if (!state.hydrating) return settleAskConnexStreamHydration(state, partial);
    return { state: mergePartial(state, partial), hydrate: false };
}

/** Reopens delta application after a failed re-hydration without consuming buffered frames. */
export function failAskConnexStreamHydration(state: AskConnexStreamState): AskConnexStreamState {
    return { ...state, hydrating: false };
}

/** Snapshot of the live streaming tail consumed by the isolated transcript tail component. */
export type AskConnexStreamSnapshot = {
    turnId: number;
    text: string;
} | null;

/** Minimal external store contract compatible with `useSyncExternalStore`. */
export type AskConnexStreamStore = {
    subscribe: (listener: () => void) => () => void;
    getSnapshot: () => AskConnexStreamSnapshot;
    publish: (snapshot: AskConnexStreamSnapshot) => void;
};

/**
 * Creates the external store that isolates per-frame streaming updates from React state. Only the
 * subscribed tail component re-renders when a new snapshot is published; the transcript above it
 * never observes delta traffic.
 */
export function createAskConnexStreamStore(): AskConnexStreamStore {
    let snapshot: AskConnexStreamSnapshot = null;
    const listeners = new Set<() => void>();
    return {
        subscribe: (listener) => {
            listeners.add(listener);
            return () => listeners.delete(listener);
        },
        getSnapshot: () => snapshot,
        publish: (next) => {
            if (next === snapshot) return;
            if (next !== null && snapshot !== null
                && next.turnId === snapshot.turnId
                && next.text === snapshot.text) return;
            snapshot = next;
            listeners.forEach((listener) => listener());
        },
    };
}

/** Coalescer handle that flushes at most once per animation frame while invalidated. */
export type AskConnexFrameCoalescer = {
    invalidate: () => void;
    dispose: () => void;
};

/**
 * Coalesces high-frequency stream mutations into at most one flush per animation frame, so delta
 * application never publishes per frame received. `invalidate` marks the pending work; the flush
 * runs on the next animation frame and re-arms only when invalidated again.
 */
export function createAskConnexFrameCoalescer(
    flush: () => void,
    schedule: (callback: FrameRequestCallback) => number,
    cancel: (handle: number) => void,
): AskConnexFrameCoalescer {
    let handle: number | null = null;
    let disposed = false;
    return {
        invalidate: () => {
            if (disposed || handle !== null) return;
            handle = schedule(() => {
                handle = null;
                if (!disposed) flush();
            });
        },
        dispose: () => {
            disposed = true;
            if (handle !== null) {
                cancel(handle);
                handle = null;
            }
        },
    };
}

/** Outcome of one cancel request against the in-flight assistant turn. */
export type AskConnexCancelOutcome =
    | 'requested'
    | 'already_settled'
    | 'forbidden'
    | 'failed'
    | 'skipped';

/**
 * Requests a server-side stop of the in-flight turn. Only an accepted or running turn with known
 * identifiers is cancellable, and an already-settled turn (404) is reported distinctly so the
 * caller can wait for durable reconciliation instead of surfacing an error.
 */
export async function requestAskConnexTurnCancel(
    turn: Pick<AskConnexTurnState, 'phase' | 'sessionId' | 'turnId'>,
    pending: boolean,
    request: (sessionId: number, turnId: number) => Promise<void>,
    statusOf: (error: unknown) => number | null,
): Promise<AskConnexCancelOutcome> {
    if (pending
        || (turn.phase !== 'accepted' && turn.phase !== 'running')
        || turn.sessionId === null
        || turn.turnId === null) {
        return 'skipped';
    }
    try {
        await request(turn.sessionId, turn.turnId);
        return 'requested';
    } catch (error) {
        const status = statusOf(error);
        if (status === 404 || status === 409) return 'already_settled';
        if (status === 403) return 'forbidden';
        return 'failed';
    }
}
