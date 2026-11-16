import { afterEach, describe, expect, it, vi } from 'vitest';
import { getContactReferences } from '@/app/lib/api';

afterEach(() => vi.unstubAllGlobals());

describe('lean contact reference hydration', () => {
    it('deduplicates valid ids and caps each authenticated request at one hundred', async () => {
        const fetch = vi.fn<typeof globalThis.fetch>(async () => new Response('[]', { headers: { 'content-type': 'application/json' } }));
        vi.stubGlobal('fetch', fetch);
        const ids = Array.from({ length: 201 }, (_, index) => index + 1);
        const controller = new AbortController();
        await getContactReferences([...ids, 1, 0, -1, 1.5, NaN], {
            signal: controller.signal, headers: { 'X-Workspace-Id': '9' },
        });
        expect(fetch).toHaveBeenCalledTimes(3);
        const requested: number[] = [];
        for (const call of fetch.mock.calls) {
            const [input, init] = call;
            const url = new URL(String(input), 'http://localhost');
            expect(url.pathname).toBe('/api/persons/references');
            const batch = url.searchParams.getAll('ids').flatMap((value) => value.split(',').map(Number));
            expect(batch.length).toBeLessThanOrEqual(100);
            requested.push(...batch);
            expect(init).toEqual(expect.objectContaining({ method: 'GET', signal: controller.signal }));
            expect(new Headers(init?.headers).get('X-Workspace-Id')).toBe('9');
        }
        expect(requested).toEqual(ids);
    });

    it('does not issue a read for an empty or invalid selection', async () => {
        const fetch = vi.fn();
        vi.stubGlobal('fetch', fetch);
        expect(await getContactReferences([])).toEqual([]);
        expect(await getContactReferences([0, -1, NaN])).toEqual([]);
        expect(fetch).not.toHaveBeenCalled();
    });
});
