import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/app/lib/api';
import { loadDetailSection } from '@/app/lib/loadDetailSection';
import { detailSectionFailure } from '@/app/lib/detailSectionResult';

const redirect = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ redirect }));

beforeEach(() => {
    redirect.mockReset().mockImplementation(() => { throw new Error('sign-in redirect'); });
});

describe('detail section request outcomes', () => {
    it('keeps a genuinely empty result successful', async () => {
        expect(await loadDetailSection(async () => [])).toEqual({ kind: 'loaded', data: [] });
    });

    it.each([403, 404, 500, 503])('keeps HTTP %s distinct from empty data without exposing diagnostics', async (status) => {
        const failure = await loadDetailSection(async () => {
            throw new ApiError('private diagnostic', status, 'INTERNAL_CODE', { secret: 'private' }, 'support-ref');
        });
        expect(failure).toEqual({ kind: 'failed', status, reference: 'support-ref' });
        expect('data' in failure).toBe(false);
        expect(redirect).not.toHaveBeenCalled();
    });

    it('keeps network failure distinct from an unreachable path or zero totals', async () => {
        expect(await loadDetailSection(async () => { throw new Error('private'); })).toEqual({ kind: 'failed' });
    });

    it.each([401, 403])('redirects an expired session (HTTP %s) instead of displaying a denial', async (status) => {
        await expect(loadDetailSection(async () => {
            throw new ApiError('', status, undefined, undefined, undefined, true);
        })).rejects.toThrow('sign-in redirect');
        expect(redirect).toHaveBeenCalledWith('/auth/login');
    });

    it('prioritizes permission denial for sections with several dependencies', () => {
        expect(detailSectionFailure(
            { kind: 'failed', status: 503 },
            { kind: 'loaded', data: [] },
            { kind: 'failed', status: 403 },
        )).toEqual({ kind: 'failed', status: 403 });
        expect(detailSectionFailure({ kind: 'loaded', data: [] })).toBeNull();
    });
});
