import { afterEach, describe, expect, it, vi } from 'vitest';

import { getRelationshipMapData, getRelationshipMapAvailability, RelationshipMapCapacityError } from '@/app/lib/api';

const paths = ['/api/companies/page', '/api/persons/page', '/api/deals/page', '/api/activities/page', '/api/tasks/page', '/api/notes/page'];

function mockPages(totals: number[], failingPath?: string, failureStatus?: number) {
    const requests: URL[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        requests.push(url);
        if (url.pathname === failingPath) {
            if (failureStatus) return new Response('{}', { status: failureStatus });
            throw new TypeError('Network unavailable');
        }
        const index = paths.indexOf(url.pathname);
        if (index < 0) throw new Error(`Unexpected request: ${url.pathname}`);
        return new Response(JSON.stringify({ items: [], total: totals[index], page: 1, size: Number(url.searchParams.get('size')) }), {
            headers: { 'Content-Type': 'application/json' },
        });
    }));
    return requests;
}

afterEach(() => vi.unstubAllGlobals());

describe('relationship map capacity', () => {
    it.each([
        [2_001, 0, 0, 0, 0, 0],
        [1_000, 1_000, 1, 0, 0, 0],
        [0, 0, 0, 4_001, 0, 0],
        [0, 0, 0, 2_000, 2_000, 1],
    ])('refuses over-cap totals %j before fetching more pages', async (...totals) => {
        const requests = mockPages(totals);
        await expect(getRelationshipMapData()).rejects.toBeInstanceOf(RelationshipMapCapacityError);
        expect(requests).toHaveLength(6);
        expect(requests.every((url) => url.searchParams.get('page') === '1')).toBe(true);
        expect(requests.find((url) => url.pathname === '/api/notes/page')?.searchParams.get('workspaceOnly')).toBe('true');
    });

    it('preserves a fetch failure as a failure rather than a capacity refusal', async () => {
        mockPages([0, 0, 0, 0, 0, 0], '/api/companies/page');
        await expect(getRelationshipMapData()).rejects.toThrow('Network unavailable');
    });

    it('allows the exact record and touch limits using bounded availability reads', async () => {
        const requests = mockPages([1_000, 500, 500, 2_000, 1_000, 1_000]);
        await expect(getRelationshipMapAvailability()).resolves.toBe('available');
        expect(requests).toHaveLength(6);
        expect(requests.every((url) => url.searchParams.get('page') === '1' && url.searchParams.get('size') === '1')).toBe(true);
    });

    it.each([
        [2_001, 0, 0, 0, 0, 0],
        [0, 0, 0, 0, 0, 4_001],
    ])('withholds focused actions for over-cap totals %j', async (...totals) => {
        mockPages(totals);
        await expect(getRelationshipMapAvailability()).resolves.toBe('overCapacity');
    });

    it.each([undefined, 503])('keeps a failed source unknown so focused navigation can recover (%s)', async (status) => {
        mockPages([0, 0, 0, 0, 0, 0], '/api/notes/page', status);
        await expect(getRelationshipMapAvailability()).resolves.toBe('unknown');
        mockPages([0, 0, 0, 0, 0, 0]);
        await expect(getRelationshipMapAvailability()).resolves.toBe('available');
    });
});
