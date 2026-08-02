import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
    addContactTagFromCookie,
    removeContactTagFromCookie,
    replaceContactTagsFromCookie,
} from '@/app/lib/api';
import type { Tag } from '@/app/lib/types';

const COOKIE = 'JSESSIONID=session; connex_workspace=1';

const TAG: Tag = {
    id: 7,
    name: 'Champion',
    color: '#5b8def',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
};

const API_SOURCE = readFileSync(path.resolve(process.cwd(), 'app/lib/api.ts'), 'utf8');

const MUTATING_HELPERS = ['postJson', 'putJson', 'patchJson', 'deleteJson', 'postFormData'] as const;

function stubFetchRejecting(error: Error): void {
    vi.stubGlobal('fetch', () => Promise.reject(error));
}

function stubFetchResponding(build: () => Response): void {
    vi.stubGlobal('fetch', () => Promise.resolve(build()));
}

/**
 * Names every fetcher routed through the error-swallowing `safeWithCookie`, by scanning for the
 * arrow that follows each call marker. Both the single-line and the wrapped call layouts in
 * `api.ts` are matched, and the count is asserted below so a regex that quietly stops matching
 * cannot turn this guard into a no-op.
 */
function fetchersRoutedThroughSafeWithCookie(): string[] {
    const marker = 'safeWithCookie<';
    const names: string[] = [];
    let index = API_SOURCE.indexOf(marker);
    while (index !== -1) {
        const arrow = /\(\s*init\s*\)\s*=>\s*(\w+)\(/.exec(API_SOURCE.slice(index, index + 240));
        if (arrow) {
            names.push(arrow[1]);
        }
        index = API_SOURCE.indexOf(marker, index + marker.length);
    }
    return names;
}

function bodyOf(fetcherName: string): string {
    const declaration = new RegExp(`function ${fetcherName}\\s*\\([^)]*\\)[^{]*\\{([\\s\\S]*?)\\n\\}`);
    const match = declaration.exec(API_SOURCE);
    if (!match) {
        throw new Error(`could not locate the definition of ${fetcherName} in app/lib/api.ts`);
    }
    return match[1];
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('SSR contact-tag writes report whether the write landed', () => {
    it('reports an unreachable backend as a failure rather than resolving successfully', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        await expect(addContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: false });
        await expect(removeContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: false });
        await expect(replaceContactTagsFromCookie(1, [TAG.id], COOKIE)).resolves.toEqual({ ok: false });
    });

    it('reports a refused write as a failure', async () => {
        stubFetchResponding(() => new Response('Requires the CONTACT_WRITE permission', { status: 403 }));

        await expect(addContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: false });
        await expect(removeContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: false });
    });

    it('reports a write attempted without a session as a failure', async () => {
        stubFetchRejecting(new Error('fetch must not be reached without a cookie'));

        await expect(addContactTagFromCookie(1, TAG.id, null)).resolves.toEqual({ ok: false });
        await expect(removeContactTagFromCookie(1, TAG.id, null)).resolves.toEqual({ ok: false });
        await expect(replaceContactTagsFromCookie(1, [TAG.id], null)).resolves.toEqual({ ok: false });
    });

    it('still reports a write that landed as a success', async () => {
        stubFetchResponding(() => new Response(null, { status: 204 }));

        await expect(addContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: true, data: undefined });
        await expect(removeContactTagFromCookie(1, TAG.id, COOKIE)).resolves.toEqual({ ok: true, data: undefined });
    });

    it('returns the resulting tag set when a replacement lands', async () => {
        stubFetchResponding(() => new Response(JSON.stringify([TAG]), { status: 200 }));

        await expect(replaceContactTagsFromCookie(1, [TAG.id], COOKIE)).resolves.toEqual({
            ok: true,
            data: [TAG],
        });
    });

    it('never answers a failed write with the empty array that reads as an applied change', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        for (const result of [
            await addContactTagFromCookie(1, TAG.id, COOKIE),
            await removeContactTagFromCookie(1, TAG.id, COOKIE),
            await replaceContactTagsFromCookie(1, [TAG.id], COOKIE),
        ]) {
            expect(Array.isArray(result)).toBe(false);
            expect(result.ok).toBe(false);
        }
    });
});

describe('the error-swallowing cookie wrapper stays off write paths', () => {
    it('routes no mutation through safeWithCookie', () => {
        const fetchers = fetchersRoutedThroughSafeWithCookie();

        expect(fetchers.length).toBeGreaterThan(30);
        for (const fetcher of fetchers) {
            const used = MUTATING_HELPERS.filter((helper) => bodyOf(fetcher).includes(helper));
            expect(used, `${fetcher} is a write and must use resultWithCookie`).toEqual([]);
        }
    });

    it('keeps the contact-tag writes on the failure-aware wrapper', () => {
        for (const wrapper of [
            'addContactTagFromCookie',
            'removeContactTagFromCookie',
            'replaceContactTagsFromCookie',
        ]) {
            expect(bodyOf(wrapper)).toContain('resultWithCookie');
            expect(bodyOf(wrapper)).not.toContain('safeWithCookie');
        }
    });
});
