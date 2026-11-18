import { afterEach, describe, expect, it, vi } from 'vitest';
import { getContactById, getContactEvidence, getDealById } from '@/app/lib/api';

vi.mock('next-intl/server', () => ({ getLocale: async () => 'en' }));
afterEach(() => vi.unstubAllGlobals());

describe('record detail server reads', () => {
    it('forwards each fresh read through fetch without a persistent-cache directive or tags', async () => {
        const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify({ id: 42 }), { status: 200 }));
        vi.stubGlobal('fetch', fetcher);
        const init = { headers: { cookie: 'session=fixture; connex_workspace=7' } };
        await getDealById(42, init);
        await getContactById(42, init);
        await getContactEvidence(42, init);
        await getDealById(42, init);
        expect(fetcher).toHaveBeenCalledTimes(4);
        expect(fetcher.mock.calls.map(([url]) => String(url).replace(/^https?:\/\/[^/]+/, ''))).toEqual([
            '/api/deals/42', '/api/persons/42', '/api/scoring/contacts/42/evidence', '/api/deals/42',
        ]);
        for (const [, options] of fetcher.mock.calls) {
            expect(options?.method).toBe('GET');
            expect(options?.cache).toBeUndefined();
            expect(options).not.toHaveProperty('next');
            expect(new Headers(options?.headers).get('cookie')).toBe(init.headers.cookie);
        }
    });
});
