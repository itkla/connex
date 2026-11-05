import { renderToStaticMarkup } from 'react-dom/server';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';

import PipelineChart from '@/app/components/dashboard/PipelineChart';
import { getDealRevenueTimeseries } from '@/app/lib/api';
import enDashboard from '@/messages/en/dashboard.json';
import jaDashboard from '@/messages/ja/dashboard.json';

vi.mock('recharts', () => ({
    Area: () => null,
    AreaChart: () => null,
    CartesianGrid: () => null,
    XAxis: () => null,
    YAxis: () => null,
}));

vi.mock('@/components/ui/chart', () => ({
    ChartContainer: () => null,
    ChartTooltip: () => null,
    ChartTooltipContent: () => null,
}));

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('deal forecast basis', () => {
    it.each([
        ['en', enDashboard, '$441K'],
        ['ja', jaDashboard, '$44.1万'],
    ] as const)('shows the full open total beyond dated chart buckets in %s', (locale, messages, expected) => {
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <PipelineChart
                    currency="USD"
                    range="90d"
                    openValue={441000}
                    series={{ closed: [], projected: [{ year: 2000, month: 1, total: 100 }] }}
                />
            </NextIntlClientProvider>,
        );

        expect(html).toContain(expected);
        expect(html).toContain(messages.DashboardPipelineChart.projections);
    });

    it.each([true, false])('requests the chosen projection basis: openOnly=%s', async (openOnly) => {
        const fetcher = vi.fn((input: string | URL | Request) => {
            expect(String(input)).toContain('/api/deals/revenue-timeseries');
            return Promise.resolve(new Response(
                JSON.stringify({ closed: [], projected: [] }),
                { status: 200, headers: { 'Content-Type': 'application/json' } },
            ));
        });
        vi.stubGlobal('fetch', fetcher);

        await getDealRevenueTimeseries('USD', 'UTC', {}, {}, openOnly);

        const request = new URL(String(fetcher.mock.calls.at(0)?.at(0)));
        expect(request.searchParams.get('currency')).toBe('USD');
        expect(request.searchParams.get('openOnly')).toBe(String(openOnly));
    });
});
