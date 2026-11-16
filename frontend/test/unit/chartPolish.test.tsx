import { cloneElement, isValidElement, type ComponentProps, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import RevenueTrend from '@/app/components/overview/analytics/RevenueTrend';
import ActivityVolume from '@/app/components/overview/analytics/ActivityVolume';
import IntroActivity from '@/app/components/overview/analytics/IntroActivity';
import ReportWidgetRenderer from '@/app/components/reports/ReportWidgetRenderer';
import DealsRevenueChart from '@/app/components/records/deals/DealsRevenueChart';
import StageRatio from '@/app/components/records/deals/StageRatio';
import DealsAging from '@/app/components/records/deals/DealsAging';
import DealActivityBreakdown from '@/app/components/records/deals/DealActivityBreakdown';
import { autoYAxisWidth, monthAxisTicks } from '@/app/lib/chartAxes';
import { NowProvider } from '@/app/hooks/useNow';
import type { Activity, DealRevenueSeries, IntroductionRecord, ReportWidgetData } from '@/app/lib/types';
import type { ChartLegendContent, ChartTooltipContent } from '@/components/ui/chart';
import enDashboard from '@/messages/en/dashboard.json';
import jaDashboard from '@/messages/ja/dashboard.json';
import enAnalytics from '@/messages/en/analytics.json';
import jaAnalytics from '@/messages/ja/analytics.json';
import enReports from '@/messages/en/reports.json';
import jaReports from '@/messages/ja/reports.json';
import enDeals from '@/messages/en/deals.json';
import jaDeals from '@/messages/ja/deals.json';

const probe = vi.hoisted(() => {
    const charts: ComponentProps<typeof import('recharts').AreaChart>[] = [];
    const xAxes: ComponentProps<typeof import('recharts').XAxis>[] = [];
    const yAxes: ComponentProps<typeof import('recharts').YAxis>[] = [];
    const pies: ComponentProps<typeof import('recharts').Pie>[] = [];
    const lines: ComponentProps<typeof import('recharts').Line>[] = [];
    return { charts, xAxes, yAxes, lines, pies, hoverKey: '2026-11' };
});

vi.mock('recharts', async () => {
    const actual = await vi.importActual<typeof import('recharts')>('recharts');
    function Children({ children }: { children?: ReactNode }) { return <>{children}</>; }
    function Chart(props: ComponentProps<typeof actual.AreaChart>) {
        probe.charts.push(props);
        return <>{props.children}</>;
    }
    return {
        ...actual,
        ResponsiveContainer: Children, AreaChart: Chart, BarChart: Chart, LineChart: Chart, PieChart: Chart,
        CartesianGrid: () => null, ReferenceLine: () => null, Pie: (props: ComponentProps<typeof actual.Pie>) => { probe.pies.push(props); return null; }, Cell: () => null, Bar: () => null,
        XAxis: (props: ComponentProps<typeof actual.XAxis>) => { probe.xAxes.push(props); return null; },
        YAxis: (props: ComponentProps<typeof actual.YAxis>) => { probe.yAxes.push(props); return null; },
        Area: (props: ComponentProps<typeof actual.Line>) => { probe.lines.push(props); return null; },
        Line: (props: ComponentProps<typeof actual.Line>) => { probe.lines.push(props); return null; },
        Tooltip: ({ content }: ComponentProps<typeof actual.Tooltip>) => isValidElement<ComponentProps<typeof ChartTooltipContent>>(content) && content.props.formatter
            ? cloneElement(content, { active: true, label: probe.hoverKey, payload: [{ graphicalItemId: 'series', name: 'projections', dataKey: 'projections', value: 6_000_000_000 }] }) : null,
        Legend: ({ content }: ComponentProps<typeof actual.Legend>) => isValidElement<ComponentProps<typeof ChartLegendContent>>(content)
            ? cloneElement(content, { payload: probe.lines.map((line) => ({ dataKey: line.dataKey, value: String(line.dataKey), color: line.stroke, payload: { strokeDasharray: line.strokeDasharray } })) }) : null,
    };
});

afterEach(() => vi.restoreAllMocks());

beforeEach(() => {
    probe.charts.length = 0;
    probe.xAxes.length = 0;
    probe.yAxes.length = 0;
    probe.lines.length = 0;
    probe.pies.length = 0;
    probe.hoverKey = '2026-11';
    vi.spyOn(Date, 'now').mockReturnValue(Date.UTC(2026, 10, 15));
});

const catalogues = {
    en: { ...enDashboard, ...enAnalytics, ...enReports, ...enDeals },
    ja: { ...jaDashboard, ...jaAnalytics, ...jaReports, ...jaDeals },
};
const series: DealRevenueSeries = {
    closed: [{ year: 2026, month: 11, total: 6_000_000_000 }],
    projected: [{ year: 2027, month: 6, total: 8_000_000_000 }],
};
function markup(children: ReactNode, locale: keyof typeof catalogues = 'en') {
    return renderToStaticMarkup(<NextIntlClientProvider locale={locale} messages={catalogues[locale]} timeZone="UTC">
        <NowProvider value={Date.UTC(2026, 10, 15)}>{children}</NowProvider>
    </NextIntlClientProvider>);
}
function report(chartType: ReportWidgetData['chartType'], unit: string): ReportWidgetData {
    return {
        widgetId: 'revenue', title: 'Revenue', chartType, dataSource: 'deals', measure: 'won_revenue', groupBy: 'owner', unit,
        total: 6_000_000_000, priorTotal: 5_000_000_000, changePercent: null,
        points: [{ key: 'owner', label: 'Owner', value: 6_000_000_000, priorValue: 5_000_000_000, sourceId: 'owner' }],
    };
}

describe('DEAL-45 / JA-12 / MOB-31 chart axes', () => {
    it.each(['en', 'ja'] as const)('uses measured formatted ticks and nonnegative margins across charts (%s)', (locale) => {
        for (const currency of ['JPY', 'USD']) {
            markup(<>
                <PipelineChart series={series} openValue={8_000_000_000} currency={currency} range="12m" />
                <RevenueTrend series={series} currency={currency} timezone="UTC" range="12m" />
                <ReportWidgetRenderer widget={report('bar', currency)} />
                <ReportWidgetRenderer widget={report('line-area', currency)} />
                <ActivityVolume granularity="month" buckets={[{ bucketIndex: 0, periodStart: '2027-01-01', call: 10000, email: 0, meeting: 0, note: 0, other: 0 }]} />
                <IntroActivity suggestions={[]} lineage={[{ id: 1, personAId: 1, personAName: 'A', personBId: 2, personBName: 'B', introducedAt: '2027-01-05 00:00:00' } satisfies IntroductionRecord]}
                    analyticsWindow={{ from: '2026-11-01', to: '2027-02-28' }} granularity="month" />
                <DealsRevenueChart series={series} currency={currency} timezone="UTC" />
            </>, locale);
        }
        expect(probe.yAxes).toHaveLength(14);
        for (const axis of probe.yAxes) {
            expect(axis.width).toBe('auto');
            expect(axis.tickMargin).toBeGreaterThan(0);
        }
        for (const chart of probe.charts) expect(chart.margin?.left ?? 0).toBeGreaterThanOrEqual(0);
        const currencyAxes = probe.yAxes.filter((axis) => axis.tickFormatter);
        expect(currencyAxes).toHaveLength(10);
        for (const axis of currencyAxes) expect(axis.tickFormatter?.(6_000_000_000, 0)).toMatch(/[¥￥$]/);
    });

    it('delegates the formatted tick and spacing to Recharts auto measurement', () => {
        const format = (value: number) => `￥${value}億`;
        expect(autoYAxisWidth(format)).toEqual({ width: 'auto', tickSize: 0, tickMargin: 8, tickFormatter: format });
    });

    it('keeps all January ticks over two years without crowding them with neighboring month ticks', () => {
        const keys = Array.from({ length: 24 }, (_, index) => new Date(Date.UTC(2026, 10 + index, 1)).toISOString().slice(0, 7));
        const ticks = monthAxisTicks(keys);
        expect(ticks).toEqual(expect.arrayContaining(['2027-01', '2028-01', '2028-10']));
        expect(ticks).not.toContain('2026-11');
        expect(new Set(ticks).size).toBe(ticks.length);
        expect(monthAxisTicks([])).toEqual([]);
    });

    it.each(['en', 'ja'] as const)('renders a full year at January on legacy and calendar revenue and activity charts (%s)', (locale) => {
        markup(<>
            <PipelineChart series={series} openValue={8_000_000_000} currency="JPY" range="12m" />
            <RevenueTrend series={series} currency="JPY" timezone="UTC" range="12m" />
            <RevenueTrend currency="JPY" timezone="UTC" periods={{ granularity: 'month', series: {
                realized: [{ periodStart: '2026-11-01', total: 1 }, { periodStart: '2027-01-01', total: 2 }], projected: [],
            } }} />
            <ActivityVolume granularity="month" buckets={['2026-11-01', '2027-01-01'].map((periodStart, bucketIndex) => ({
                periodStart, bucketIndex, call: 1, email: 0, meeting: 0, note: 0, other: 0,
            }))} />
        </>, locale);
        expect(probe.xAxes).toHaveLength(4);
        for (const axis of probe.xAxes) {
            expect(axis.interval).toBe(0);
            const january = axis.ticks?.find((tick) => String(tick).startsWith('2027-01'));
            expect(january).toBeDefined();
            expect(axis.tickFormatter?.(january, 0)).toContain('2027');
        }
    });

    it('renders the revenue legend with solid realized and dashed all-deal swatches', () => {
        const html = markup(<RevenueTrend series={series} currency="USD" timezone="UTC" />);
        expect(html).toContain(enAnalytics.AnalyticsRevenue.actual);
        expect(html).toContain(enAnalytics.AnalyticsRevenue.projected);
        expect(html.match(/<line /g)).toHaveLength(2);
        expect(html.match(/stroke-dasharray="5 4"/g)).toHaveLength(1);
    });
});

describe('DEAL-24 readable pipeline tooltip and MOB-31 tile names', () => {
    it.each(['en', 'ja'] as const)('uses the displayed month label and revenue vocabulary (%s)', (locale) => {
        const html = markup(<PipelineChart series={series} openValue={8_000_000_000} currency="JPY" range="12m" />, locale);
        const axis = probe.xAxes[0];
        const label = axis.tickFormatter?.('2026-11', 0);
        expect(html).toContain(String(label));
        expect(html).not.toContain('2026-10');
        expect(html).not.toContain('2026-11');
        expect(html).toContain(locale === 'en' ? 'Realized revenue' : '実績売上');
        expect(html).toContain(locale === 'en' ? 'Projected revenue' : '予測売上');
        const bucket = probe.charts[0].data?.find((point: unknown) => typeof point === 'object' && point !== null && 'key' in point && point.key === '2026-11');
        expect(bucket).toMatchObject({ profit: 6_000_000_000 });
    });
});

describe('A11Y-21 chart names', () => {
    it.each(['en', 'ja'] as const)('exposes localized data summaries and disables chart tab stops (%s)', (locale) => {
        const html = markup(<>
            <DealsRevenueChart series={series} currency="JPY" timezone="UTC" />
            <StageRatio currency="JPY" distribution={[{ stageId: 1, pipelineId: 1, openCount: 7, closedCount: 3, openValue: 1, closedValue: 2 }]} />
            <DealsAging stageById={new Map()} aging={[{ stageId: 1, fresh: 1, active: 2, aging: 3, stalled: 4 }]} />
            <DealActivityBreakdown activities={[{ id: 1, type: 'Call', subject: 'Call', createdById: 1 } satisfies Activity]} />
        </>, locale);
        expect(html.match(/role="img"/g)).toHaveLength(4);
        expect(html.match(/aria-label="[^"]+"/g)).toHaveLength(4);
        expect(html).toContain(locale === 'en' ? '7 open deals' : '進行中 7件');
        expect(html).toContain(locale === 'en' ? '1 activity' : '合計1件');
        expect(probe.charts).toHaveLength(4);
        expect(probe.pies).toHaveLength(3);
        for (const pie of probe.pies) expect(pie.rootTabIndex).toBe(-1);
        for (const chart of probe.charts) expect(chart.accessibilityLayer).toBe(false);
    });
});
