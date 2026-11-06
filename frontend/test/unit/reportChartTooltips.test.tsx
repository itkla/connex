import { cloneElement, isValidElement, type ComponentProps, type ReactNode } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import ReportWidgetRenderer from '@/app/components/reports/ReportWidgetRenderer';
import type { ReportWidgetData } from '@/app/lib/types';
import type { ChartTooltipContent } from '@/components/ui/chart';
import en from '@/messages/en/reports.json';
import ja from '@/messages/ja/reports.json';

const hover = vi.hoisted(() => ({ current: 1_450_000.257, prior: 1_020_000.753 }));

vi.mock('recharts', async () => {
    const actual = await vi.importActual<typeof import('recharts')>('recharts');
    function Children({ children }: { children?: ReactNode }) {
        return <>{children}</>;
    }
    return {
        ...actual,
        ResponsiveContainer: Children,
        AreaChart: Children,
        BarChart: Children,
        FunnelChart: Children,
        PieChart: Children,
        Area: () => null,
        Bar: () => null,
        Funnel: () => null,
        Pie: () => null,
        CartesianGrid: () => null,
        XAxis: () => null,
        YAxis: () => null,
        Tooltip: ({ content }: ComponentProps<typeof actual.Tooltip>) => {
            if (!isValidElement<ComponentProps<typeof ChartTooltipContent>>(content)) return null;
            return <aside data-testid="report-tooltip">{cloneElement(content, {
                active: true,
                payload: [
                    { graphicalItemId: 'current-series', name: 'current', dataKey: 'current', value: hover.current, color: 'var(--chart-1)' },
                    { graphicalItemId: 'prior-series', name: 'prior', dataKey: 'prior', value: hover.prior, color: 'var(--chart-2)' },
                ],
            })}</aside>;
        },
    };
});

const catalogues = { en, ja };

beforeEach(() => {
    hover.current = 1_450_000.257;
    hover.prior = 1_020_000.753;
});

describe.each(['en', 'ja'] as const)('report tooltip precision in %s', (locale) => {
    it.each(['bar', 'line-area', 'funnel', 'donut'] as const)('keeps nearby counts distinct in %s tooltips', (chartType) => {
        hover.current = 10_001;
        hover.prior = 10_049;
        const dimensions: Pick<ReportWidgetData, 'dataSource' | 'groupBy'>[] = [
            { dataSource: 'activities', groupBy: 'date' },
            { dataSource: 'activities', groupBy: 'owner' },
            { dataSource: 'tasks', groupBy: 'status' },
        ];
        for (const dimension of dimensions) {
            for (const unit of ['count', null]) {
                const widget: ReportWidgetData = {
                    ...dimension,
                    widgetId: 'volume', title: 'Volume', chartType, measure: 'count',
                    unit, total: hover.current, priorTotal: hover.prior, changePercent: null,
                    points: [{ key: 'volume', label: 'Volume', value: hover.current, priorValue: hover.prior, sourceId: 'volume' }],
                };
                const markup = renderToStaticMarkup(
                    <NextIntlClientProvider locale={locale} messages={catalogues[locale]} timeZone="UTC">
                        <ReportWidgetRenderer widget={widget} />
                    </NextIntlClientProvider>,
                );
                const tooltip = markup.match(/<aside data-testid="report-tooltip">([\s\S]*?)<\/aside>/)?.[1];
                expect(tooltip).toBeDefined();
                expect(tooltip).toContain('10,001');
                expect(tooltip).toContain('10,049');
                expect(tooltip).not.toContain('10K');
                expect(tooltip).not.toContain('1万');
            }
        }
    });

    it.each(['bar', 'line-area', 'funnel', 'donut'] as const)('formats the %s current and prior values using the report currency and locale', (chartType) => {
        for (const unit of ['USD', 'JPY', 'KWD']) {
            const widget: ReportWidgetData = {
                widgetId: 'revenue', title: 'Revenue', chartType, dataSource: 'deals', measure: 'won_revenue',
                groupBy: 'owner', unit, total: hover.current, priorTotal: hover.prior, changePercent: null,
                points: [{ key: 'owner', label: 'Owner', value: hover.current, priorValue: hover.prior, sourceId: 'revenue-owner' }],
            };
            const markup = renderToStaticMarkup(
                <NextIntlClientProvider locale={locale} messages={catalogues[locale]} timeZone="UTC">
                    <ReportWidgetRenderer widget={widget} />
                </NextIntlClientProvider>,
            );
            const tooltip = markup.match(/<aside data-testid="report-tooltip">([\s\S]*?)<\/aside>/)?.[1];
            expect(tooltip).toBeDefined();
            const currencyFormat = new Intl.NumberFormat(locale, { style: 'currency', currency: unit, minimumFractionDigits: 0 });
            expect(tooltip).toContain(currencyFormat.format(hover.current));
            expect(tooltip).toContain(currencyFormat.format(hover.prior));
            expect(tooltip).toContain(catalogues[locale].Reports.document.currentPeriod);
            expect(tooltip).toContain(catalogues[locale].Reports.document.priorPeriod);
        }
    });
});
