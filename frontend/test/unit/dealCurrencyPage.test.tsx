import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DealsPage from '@/app/(app)/records/deals/page';
import { getDealsPage } from '@/app/lib/api';
import type { DealMetrics } from '@/app/lib/types';

const metrics: DealMetrics = {
    totalCount: 4,
    byCurrency: [
        { currency: 'JPY', openCount: 3, closedCount: 0, openValue: 900000, closedForecast: 0, closedRevenue: 0, wonCount: 0, lostCount: 0 },
        { currency: 'USD', openCount: 1, closedCount: 0, openValue: 500, closedForecast: 0, closedRevenue: 0, wonCount: 0, lostCount: 0 },
    ],
};

vi.mock('next/headers', () => ({ headers: async () => new Headers() }));
vi.mock('next/navigation', () => ({ redirect: vi.fn() }));
vi.mock('@/app/lib/workspaceSnapshot', () => ({ resolveWorkspaceTimezone: () => 'UTC' }));
vi.mock('@/app/components/records/deals/DealsBrowser', () => ({ default: () => <div>Deals</div> }));
vi.mock('@/app/lib/api', () => ({
    getCurrentUserResultFromCookie: vi.fn(async () => ({ ok: true, data: { id: 1 } })),
    getMyWorkspacesFromCookie: vi.fn(async () => ({})),
    getDealMetricsFromCookie: vi.fn(),
    getDealFacetsFromCookie: vi.fn(async () => ({
        status: [], stages: [], pipelines: [], companies: [], owners: [], risk: [],
        currencies: [{ key: 'JPY', count: 3 }, { key: 'USD', count: 1 }],
    })),
    getSavedViewsResultFromCookie: vi.fn(async () => ({ ok: true, data: [] })),
    getDefaultSavedViewFromCookie: vi.fn(async () => null),
    getDealsPage: vi.fn(async () => ({ items: [], total: 0 })),
}));

beforeEach(async () => {
    vi.clearAllMocks();
    const { getDealMetricsFromCookie } = await import('@/app/lib/api');
    vi.mocked(getDealMetricsFromCookie).mockResolvedValue(metrics);
});

describe('currency-scoped Deals deep links', () => {
    it('loads the requested minority currency on the server instead of the dominant bucket', async () => {
        renderToStaticMarkup(await DealsPage({ searchParams: Promise.resolve({ currency: 'USD', status: 'open' }) }));
        expect(getDealsPage).toHaveBeenCalledWith(
            expect.objectContaining({ currency: 'USD', status: ['open'] }), expect.anything(),
        );
    });

    it('keeps the dominant currency when no currency was requested', async () => {
        await DealsPage({ searchParams: Promise.resolve({}) });
        expect(getDealsPage).toHaveBeenCalledWith(expect.objectContaining({ currency: 'JPY' }), expect.anything());
    });

    it('rejects a blank currency instead of allowing an unscoped monetary query', async () => {
        await DealsPage({ searchParams: Promise.resolve({ currency: ' \t\u3000' }) });
        expect(getDealsPage).toHaveBeenCalledWith(expect.objectContaining({ currency: 'JPY' }), expect.anything());
    });

    it.each(['JPY,USD', '<script>', 'UNKNOWN', 'X'.repeat(200)])('rejects an unknown currency %s', async (currency) => {
        await DealsPage({ searchParams: Promise.resolve({ currency }) });
        expect(getDealsPage).toHaveBeenCalledWith(expect.objectContaining({ currency: 'JPY' }), expect.anything());
    });

    it('canonicalizes a known currency while preserving a zero-match status filter', async () => {
        const { getDealMetricsFromCookie } = await import('@/app/lib/api');
        vi.mocked(getDealMetricsFromCookie).mockResolvedValue({ totalCount: 3, byCurrency: metrics.byCurrency.filter(({ currency }) => currency === 'JPY') });
        await DealsPage({ searchParams: Promise.resolve({ currency: ' usd ', status: 'open' }) });
        expect(getDealsPage).toHaveBeenCalledWith(
            expect.objectContaining({ currency: 'USD', status: ['open'] }), expect.anything(),
        );
    });
});
