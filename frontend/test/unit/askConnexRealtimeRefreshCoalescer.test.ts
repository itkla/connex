import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    ASK_CONNEX_REALTIME_REFRESH_DELAY_MS,
    createRealtimeRefreshCoalescer,
    type RealtimeRefreshCoalescerOptions,
} from '@/app/components/ask-connex/realtimeRefreshCoalescer';

function setup() {
    const run = vi.fn<RealtimeRefreshCoalescerOptions['run']>().mockResolvedValue(undefined);
    const controller = new AbortController();
    const coalescer = createRealtimeRefreshCoalescer({
        run,
        delayMs: ASK_CONNEX_REALTIME_REFRESH_DELAY_MS,
    });
    return { run, controller, coalescer };
}

describe('Ask Connex realtime refresh coalescing', () => {
    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { vi.useRealTimers(); });

    it('coalesces 48 rapid enqueues into one run without extending the timer', async () => {
        const { run, controller, coalescer } = setup();
        for (let index = 0; index < 47; index++) coalescer.enqueue(1, controller.signal);
        expect(vi.getTimerCount()).toBe(1);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS - 1);
        coalescer.enqueue(1, controller.signal);
        expect(run).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(run).toHaveBeenCalledExactlyOnceWith(new Set([1]), controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledOnce();
    });

    it('includes every session invalidated within the same window', async () => {
        const { run, controller, coalescer } = setup();
        coalescer.enqueue(1, controller.signal);
        coalescer.enqueue(2, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledExactlyOnceWith(new Set([1, 2]), controller.signal);
    });

    it.each(['resolve', 'reject'] as const)('preserves enqueues during a run that will %s for exactly one later run', async (outcome) => {
        const { run, controller, coalescer } = setup();
        const first = Promise.withResolvers<void>();
        const starts: number[] = [];
        run.mockImplementation(async () => {
            starts.push(Date.now());
            if (starts.length === 1) await first.promise;
        });
        coalescer.enqueue(1, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        const enqueuedAt = Date.now();
        for (let index = 0; index < 48; index++) coalescer.enqueue(1, controller.signal);
        coalescer.enqueue(2, controller.signal);
        expect(run.mock.calls[0]?.[0]).toEqual(new Set([1]));
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS * 3);
        expect(run).toHaveBeenCalledOnce();
        expect(vi.getTimerCount()).toBe(0);

        if (outcome === 'resolve') first.resolve();
        else first.reject(new Error('Refresh failed'));
        await vi.advanceTimersByTimeAsync(0);
        expect(vi.getTimerCount()).toBe(1);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS - 1);
        expect(run).toHaveBeenCalledOnce();
        await vi.advanceTimersByTimeAsync(1);
        expect(run).toHaveBeenCalledTimes(2);
        expect(run).toHaveBeenLastCalledWith(new Set([1, 2]), controller.signal);
        expect(starts[1]).toBeGreaterThan(enqueuedAt);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledTimes(2);
    });

    it('accepts later enqueues after a rejected run settles', async () => {
        const { run, controller, coalescer } = setup();
        run.mockRejectedValueOnce(new Error('Refresh failed'));
        coalescer.enqueue(1, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        coalescer.enqueue(2, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledTimes(2);
        expect(run).toHaveBeenLastCalledWith(new Set([2]), controller.signal);
    });

    it('replaces an old pending batch when the abort signal changes', async () => {
        const { run, controller, coalescer } = setup();
        const next = new AbortController();
        coalescer.enqueue(1, controller.signal);
        coalescer.enqueue(2, next.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledExactlyOnceWith(new Set([2]), next.signal);
    });

    it('replaces dirty work across identities while leaving the old run in flight', async () => {
        const { run, controller, coalescer } = setup();
        const first = Promise.withResolvers<void>();
        run.mockReturnValueOnce(first.promise);
        coalescer.enqueue(1, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        coalescer.enqueue(2, controller.signal);
        controller.abort();
        const next = new AbortController();
        coalescer.enqueue(3, next.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledOnce();
        first.resolve();
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledTimes(2);
        expect(run).toHaveBeenLastCalledWith(new Set([3]), next.signal);
    });

    it.each(['before enqueue', 'before timer'] as const)('drops a batch aborted %s and accepts a new identity', async (when) => {
        const { run, controller, coalescer } = setup();
        if (when === 'before enqueue') controller.abort();
        coalescer.enqueue(1, controller.signal);
        controller.abort();
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).not.toHaveBeenCalled();
        const next = new AbortController();
        coalescer.enqueue(2, next.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledExactlyOnceWith(new Set([2]), next.signal);
    });

    it('cancels a pending timer on dispose and ignores future enqueues', async () => {
        const { run, controller, coalescer } = setup();
        coalescer.enqueue(1, controller.signal);
        expect(vi.getTimerCount()).toBe(1);
        coalescer.dispose();
        expect(vi.getTimerCount()).toBe(0);
        coalescer.enqueue(2, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).not.toHaveBeenCalled();
    });

    it('discards dirty work on dispose without aborting an in-flight run', async () => {
        const { run, controller, coalescer } = setup();
        const first = Promise.withResolvers<void>();
        run.mockReturnValueOnce(first.promise);
        coalescer.enqueue(1, controller.signal);
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        coalescer.enqueue(2, controller.signal);
        coalescer.dispose();
        expect(controller.signal.aborted).toBe(false);
        first.resolve();
        await vi.advanceTimersByTimeAsync(ASK_CONNEX_REALTIME_REFRESH_DELAY_MS);
        expect(run).toHaveBeenCalledOnce();
        expect(vi.getTimerCount()).toBe(0);
    });
});
