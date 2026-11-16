import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';

import MapPage from '@/app/(app)/intelligence/map/page';
import enMap from '@/messages/en/map.json';

vi.mock('next/headers', () => ({ headers: async () => new Headers({ cookie: 'connex_workspace=7' }) }));
vi.mock('next-intl/server', async () => {
    const { createTranslator } = await import('next-intl');
    const { default: messages } = await import('@/messages/en/map.json');
    return { getTranslations: async () => createTranslator({ locale: 'en', messages, namespace: 'MapPage' }) };
});
vi.mock('@/app/components/map/MapView', () => ({ default: () => <div>Map</div> }));

function stubMapReads(error?: Error) {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
        const url = new URL(input instanceof Request ? input.url : String(input));
        let body: unknown = [];
        if (url.pathname === '/api/auth/me') body = { id: 1 };
        if (url.pathname === '/api/workspaces') body = { activeWorkspaceId: 7, workspaces: [] };
        if (url.pathname.endsWith('/page')) {
            if (url.pathname === '/api/companies/page' && error) throw error;
            body = { items: [], total: url.pathname === '/api/companies/page' ? 2_001 : 0, page: 1, size: 100 };
        }
        return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
    }));
}

afterEach(() => vi.unstubAllGlobals());

describe('map recovery paths', () => {
    it.each([
        [{}, null],
        [{ contactId: '42' }, '/records/contacts/42'],
        [{ companyId: '17' }, '/records/companies/17'],
        [{ contactId: '42', companyId: '17' }, '/records/contacts/42'],
        [{ companyId: 'invalid' }, null],
        [{ contactId: '-1' }, null],
    ])('offers ordinary navigation for a capacity refusal with %j', async (searchParams, recordHref) => {
        stubMapReads();
        const html = renderToStaticMarkup(await MapPage({ searchParams: Promise.resolve(searchParams) }));
        expect(html).toContain(enMap.MapPage.limitTitle);
        for (const kind of ['contacts', 'companies', 'deals']) {
            expect(html).toContain(`href="/records/${kind}"`);
        }
        if (recordHref) {
            expect(html).toContain(`href="${recordHref}"`);
            expect(html).toContain(enMap.MapPage.openRecord);
        } else {
            expect(html).not.toContain(enMap.MapPage.openRecord);
        }
    });

    it.each([new TypeError('Network unavailable'), new Error('Forbidden')])('lets %s reach the intelligence retry boundary', async (error) => {
        stubMapReads(error);
        await expect(MapPage({ searchParams: Promise.resolve({}) })).rejects.toBe(error);
    });
});
