import { describe, expect, it, vi } from 'vitest';

import {
    ASK_CONNEX_STREAM_HYDRATION_LIMIT,
    absorbAskConnexStreamPartial,
    applyAskConnexStreamDelta,
    createAskConnexFrameCoalescer,
    createAskConnexStream,
    createAskConnexStreamStore,
    failAskConnexStreamHydration,
    requestAskConnexTurnCancel,
    settleAskConnexStreamHydration,
    shouldResetAskConnexStream,
    type AskConnexStreamState,
} from '@/app/lib/askConnexStream';
import type { AiChatDeltaFrame, AiChatRealtimeFrame } from '@/app/lib/types';

function delta(seq: number, text: string, turnId = 9): AiChatDeltaFrame {
    return { turnId, seq, kind: 'delta', text };
}

function applyAll(state: AskConnexStreamState, frames: AiChatDeltaFrame[]): AskConnexStreamState {
    return frames.reduce(
        (current, frame) => applyAskConnexStreamDelta(current, frame).state,
        state,
    );
}

describe('Ask Connex stream reassembly', () => {
    it('recognizes only the durable reset frame that invalidates an existing stream', () => {
        const state = applyAll(createAskConnexStream(9), [delta(0, 'Abandoned')]);
        const reset: AiChatRealtimeFrame = {
            workspaceId: 7,
            sessionId: 4,
            turnId: 9,
            seq: 0,
            kind: 'reset',
            tool: null,
            status: 'running',
            reason: null,
        };

        expect(shouldResetAskConnexStream(state, reset)).toBe(true);
        expect(shouldResetAskConnexStream(null, reset)).toBe(false);
        expect(shouldResetAskConnexStream(state, { ...reset, turnId: 10 })).toBe(false);
        expect(shouldResetAskConnexStream(state, { ...reset, kind: 'step' })).toBe(false);
        expect(shouldResetAskConnexStream(state, { ...reset, kind: 'state' })).toBe(false);
    });

    it('reassembles contiguous offset frames and drops replayed overlap', () => {
        const state = applyAll(createAskConnexStream(9), [
            delta(0, 'Hel'),
            delta(3, 'lo, wo'),
            delta(3, 'lo, wo'),
            delta(0, 'Hel'),
            delta(9, 'rld!'),
        ]);

        expect(state.text).toBe('Hello, world!');
        expect(state.pending).toHaveLength(0);
        expect(state.hydrating).toBe(false);
    });

    it('applies only the unapplied suffix of a frame straddling the applied length', () => {
        const started = applyAll(createAskConnexStream(9), [delta(0, 'Hello, wo')]);
        const straddling = applyAskConnexStreamDelta(started, delta(7, 'world!'));

        expect(straddling.state.text).toBe('Hello, world!');
        expect(straddling.hydrate).toBe(false);
    });

    it('ignores frames addressed to another turn', () => {
        const state = createAskConnexStream(9);
        const transition = applyAskConnexStreamDelta(state, delta(0, 'other', 10));

        expect(transition.state).toBe(state);
        expect(transition.hydrate).toBe(false);
    });

    it('buffers a gapped frame, requests hydration once, and reattaches after the partial', () => {
        const started = applyAll(createAskConnexStream(9), [delta(0, 'Hel')]);
        const gapped = applyAskConnexStreamDelta(started, delta(9, 'rld!'));

        expect(gapped.hydrate).toBe(true);
        expect(gapped.state.hydrating).toBe(true);
        expect(gapped.state.text).toBe('Hel');
        expect(gapped.state.pending).toHaveLength(1);

        const buffered = applyAskConnexStreamDelta(gapped.state, delta(13, ' Bye'));
        expect(buffered.hydrate).toBe(false);
        expect(buffered.state.pending).toHaveLength(2);

        const settled = settleAskConnexStreamHydration(buffered.state, 'Hello, wo');
        expect(settled.state.text).toBe('Hello, world! Bye');
        expect(settled.state.pending).toHaveLength(0);
        expect(settled.state.hydrating).toBe(false);
        expect(settled.hydrate).toBe(false);
    });

    it('keeps the gap open and requests another hydration when the partial is still short', () => {
        const gapped = applyAskConnexStreamDelta(createAskConnexStream(9), delta(9, 'rld!'));
        const settled = settleAskConnexStreamHydration(gapped.state, 'Hel');

        expect(settled.state.text).toBe('Hel');
        expect(settled.state.pending).toHaveLength(1);
        expect(settled.state.hydrating).toBe(true);
        expect(settled.hydrate).toBe(true);
    });

    it('keeps one in-flight hydration authoritative when polling absorbs a short partial', () => {
        const gapped = applyAskConnexStreamDelta(createAskConnexStream(9), delta(9, 'rld!'));
        const absorbed = absorbAskConnexStreamPartial(gapped.state, 'Hel');

        expect(absorbed.state.text).toBe('Hel');
        expect(absorbed.state.pending).toHaveLength(1);
        expect(absorbed.state.hydrating).toBe(true);
        expect(absorbed.state.hydrations).toBe(1);
        expect(absorbed.hydrate).toBe(false);

        const settled = settleAskConnexStreamHydration(absorbed.state, 'Hello, wo');
        expect(settled.state.text).toBe('Hello, world!');
        expect(settled.state.pending).toHaveLength(0);
        expect(settled.state.hydrating).toBe(false);
    });

    it('dedupes live frames against a late-join hydrated partial by character offset', () => {
        const hydrated = settleAskConnexStreamHydration(createAskConnexStream(9), 'Hello, wo');
        expect(hydrated.state.text).toBe('Hello, wo');
        expect(hydrated.hydrate).toBe(false);

        const covered = applyAskConnexStreamDelta(hydrated.state, delta(3, 'lo, wo'));
        expect(covered.state.text).toBe('Hello, wo');

        const straddling = applyAskConnexStreamDelta(covered.state, delta(7, 'world!'));
        expect(straddling.state.text).toBe('Hello, world!');
    });

    it('never shortens live text when the persisted partial lags behind', () => {
        const ahead = applyAll(createAskConnexStream(9), [delta(0, 'Hello, world')]);
        const settled = settleAskConnexStreamHydration(ahead, 'Hello');

        expect(settled.state.text).toBe('Hello, world');
    });

    it('stops requesting hydration after the bounded retry limit', () => {
        let state = applyAskConnexStreamDelta(createAskConnexStream(9), delta(50, 'tail')).state;
        let requests = 1;
        while (settleAskConnexStreamHydration(state, '').hydrate) {
            state = settleAskConnexStreamHydration(state, '').state;
            requests += 1;
        }

        expect(requests).toBe(ASK_CONNEX_STREAM_HYDRATION_LIMIT);
        expect(settleAskConnexStreamHydration(state, '').state.hydrating).toBe(false);

        const afterCap = applyAskConnexStreamDelta(
            settleAskConnexStreamHydration(state, '').state,
            delta(60, 'more'),
        );
        expect(afterCap.hydrate).toBe(false);
    });

    it('reopens delta application after a failed hydration without losing buffered frames', () => {
        const gapped = applyAskConnexStreamDelta(createAskConnexStream(9), delta(9, 'rld!'));
        const failed = failAskConnexStreamHydration(gapped.state);

        expect(failed.hydrating).toBe(false);
        expect(failed.pending).toHaveLength(1);
    });
});

describe('Ask Connex stream frame batching', () => {
    it('coalesces many invalidations into one flush per animation frame', () => {
        const frames = new Map<number, FrameRequestCallback>();
        let nextHandle = 1;
        const flush = vi.fn();
        const coalescer = createAskConnexFrameCoalescer(
            flush,
            (callback) => {
                const handle = nextHandle++;
                frames.set(handle, callback);
                return handle;
            },
            (handle) => frames.delete(handle),
        );
        const runFrame = () => {
            const callbacks = [...frames.values()];
            frames.clear();
            callbacks.forEach((callback) => callback(0));
        };

        coalescer.invalidate();
        coalescer.invalidate();
        coalescer.invalidate();
        expect(frames.size).toBe(1);
        runFrame();
        expect(flush).toHaveBeenCalledTimes(1);

        coalescer.invalidate();
        runFrame();
        expect(flush).toHaveBeenCalledTimes(2);

        coalescer.invalidate();
        coalescer.dispose();
        expect(frames.size).toBe(0);
        coalescer.invalidate();
        runFrame();
        expect(flush).toHaveBeenCalledTimes(2);
    });

    it('publishes distinct snapshots to subscribers and skips unchanged ones', () => {
        const store = createAskConnexStreamStore();
        const listener = vi.fn();
        const unsubscribe = store.subscribe(listener);

        store.publish(null);
        expect(listener).not.toHaveBeenCalled();

        store.publish({ turnId: 9, text: 'Hel' });
        expect(listener).toHaveBeenCalledTimes(1);
        expect(store.getSnapshot()).toEqual({ turnId: 9, text: 'Hel' });

        store.publish({ turnId: 9, text: 'Hel' });
        expect(listener).toHaveBeenCalledTimes(1);

        store.publish({ turnId: 9, text: 'Hello' });
        expect(listener).toHaveBeenCalledTimes(2);

        unsubscribe();
        store.publish(null);
        expect(listener).toHaveBeenCalledTimes(2);
        expect(store.getSnapshot()).toBeNull();
    });
});

class FakeApiError extends Error {
    constructor(readonly status: number) {
        super(`HTTP ${status}`);
    }
}

describe('Ask Connex turn cancellation', () => {
    const activeTurn = { phase: 'running', sessionId: 4, turnId: 9 } as const;
    const statusOf = (error: unknown) => (error instanceof FakeApiError ? error.status : null);

    it('requests the cancel endpoint with the active session and turn', async () => {
        const request = vi.fn(() => Promise.resolve());

        await expect(requestAskConnexTurnCancel(activeTurn, false, request, statusOf))
            .resolves.toBe('requested');
        expect(request).toHaveBeenCalledWith(4, 9);
    });

    it('skips when the turn is settled, unidentified, or a cancel is already pending', async () => {
        const request = vi.fn(() => Promise.resolve());

        await expect(requestAskConnexTurnCancel(
            { phase: 'resolved', sessionId: 4, turnId: 9 }, false, request, statusOf,
        )).resolves.toBe('skipped');
        await expect(requestAskConnexTurnCancel(
            { phase: 'running', sessionId: null, turnId: null }, false, request, statusOf,
        )).resolves.toBe('skipped');
        await expect(requestAskConnexTurnCancel(activeTurn, true, request, statusOf))
            .resolves.toBe('skipped');
        expect(request).not.toHaveBeenCalled();
    });

    it('maps authorization and settlement responses to distinct outcomes', async () => {
        await expect(requestAskConnexTurnCancel(
            activeTurn, false, () => Promise.reject(new FakeApiError(404)), statusOf,
        )).resolves.toBe('already_settled');
        await expect(requestAskConnexTurnCancel(
            activeTurn, false, () => Promise.reject(new FakeApiError(409)), statusOf,
        )).resolves.toBe('already_settled');
        await expect(requestAskConnexTurnCancel(
            activeTurn, false, () => Promise.reject(new FakeApiError(403)), statusOf,
        )).resolves.toBe('forbidden');
        await expect(requestAskConnexTurnCancel(
            activeTurn, false, () => Promise.reject(new Error('offline')), statusOf,
        )).resolves.toBe('failed');
    });
});
