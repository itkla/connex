import { describe, expect, it, vi } from 'vitest';

import { AiGenerationError, resolveAiGeneration } from '@/app/lib/aiGeneration';
import type { AiGenerationStatus } from '@/app/lib/types';

const handle = 'f40f5943-9943-4c79-94d2-2e2a014cff46';
const expiresAt = '2026-08-08T10:02:00.000Z';

function running(
    status: 'accepted' | 'running',
): Extract<AiGenerationStatus<string>, { status: 'accepted' | 'running' }> {
    return {
        handle,
        kind: 'deal.brief',
        status,
        result: null,
        reason: null,
        retryAfterMs: 2_000,
        expiresAt,
    };
}

describe('bounded AI generation polling', () => {
    it('resolves accepted through running without reissuing generation', async () => {
        const poll = vi.fn()
            .mockResolvedValueOnce(running('running'))
            .mockResolvedValueOnce({
                ...running('running'),
                status: 'resolved',
                result: 'ready',
            } satisfies AiGenerationStatus<string>);
        const sleep = vi.fn().mockResolvedValue(undefined);

        await expect(resolveAiGeneration(running('accepted'), poll, {
            now: () => Date.parse('2026-08-08T10:00:00.000Z'),
            sleep,
        })).resolves.toBe('ready');

        expect(poll).toHaveBeenCalledTimes(2);
        expect(poll).toHaveBeenNthCalledWith(1, handle);
        expect(sleep).toHaveBeenCalledTimes(2);
    });

    it('surfaces provider failure as failed', async () => {
        const failed: AiGenerationStatus<string> = {
            ...running('running'),
            status: 'failed',
            result: null,
            reason: 'provider_error',
        };

        await expect(resolveAiGeneration(failed, vi.fn())).rejects.toEqual(
            new AiGenerationError('failed', 'provider_error'),
        );
    });

    it('surfaces server timeout distinctly from failure', async () => {
        const timedOut: AiGenerationStatus<string> = {
            ...running('running'),
            status: 'timed_out',
            result: null,
            reason: 'generation_timeout',
        };

        await expect(resolveAiGeneration(timedOut, vi.fn())).rejects.toMatchObject({
            status: 'timed_out',
            reason: 'generation_timeout',
        });
    });

    it('bounds transient transport retries by the declared poll window', async () => {
        let now = Date.parse('2026-08-08T10:01:59.000Z');
        const sleep = vi.fn().mockImplementation(async (milliseconds: number) => {
            now += milliseconds;
        });

        await expect(resolveAiGeneration(
            running('accepted'),
            vi.fn().mockRejectedValue(new TypeError('network')),
            {
                now: () => now,
                sleep,
                shouldRetryError: () => true,
            },
        )).rejects.toMatchObject({
            status: 'timed_out',
            reason: 'poll_window_expired',
        });
    });
});
