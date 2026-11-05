import { NextIntlClientProvider } from 'next-intl';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import ReportWidgetRenderer, { formatReportValue } from '@/app/components/reports/ReportWidgetRenderer';
import type { ReportWidgetData } from '@/app/lib/types';
import en from '@/messages/en/reports.json';
import ja from '@/messages/ja/reports.json';

const catalogues = { en, ja };

function mixedWidget(chartType: ReportWidgetData['chartType']): ReportWidgetData {
    return {
        widgetId: 'revenue',
        title: 'Revenue',
        chartType,
        dataSource: 'deals',
        measure: 'won_revenue',
        groupBy: 'owner',
        unit: 'mixed',
        total: null,
        priorTotal: null,
        changePercent: null,
        points: [
            { key: 'USD:owner', label: 'USD · Owner', value: 1_450_000.25, priorValue: 1_020_000.75, sourceId: 'usd' },
            { key: 'JPY:owner', label: 'JPY · Owner', value: 1_733_430_000, priorValue: null, sourceId: 'jpy' },
        ],
    };
}

function renderWidget(widget: ReportWidgetData, locale: keyof typeof catalogues): string {
    return renderToStaticMarkup(
        <NextIntlClientProvider locale={locale} messages={catalogues[locale]} timeZone="UTC">
            <ReportWidgetRenderer widget={widget} />
        </NextIntlClientProvider>,
    );
}

describe('report monetary precision', () => {
    it.each(['en', 'ja'])('retains full monetary amounts and cents in %s', (locale) => {
        expect(formatReportValue(1_450_000.25, 'USD', locale)).toBe('$1,450,000.25');
        expect(formatReportValue(1_020_000.75, 'USD', locale)).toBe('$1,020,000.75');
        expect(formatReportValue(1_733_430_000, 'JPY', locale))
            .toBe(locale === 'ja' ? '￥1,733,430,000' : '¥1,733,430,000');
        expect(formatReportValue(1_000.25, 'JPY', locale))
            .toBe(locale === 'ja' ? '￥1,000.25' : '¥1,000.25');
        expect(formatReportValue(-10_000.25, 'USD', locale)).toBe('-$10,000.25');
        expect(formatReportValue(0, 'USD', locale)).toBe('$0');
    });

    it('keeps useful precision on compact monetary axes', () => {
        expect(formatReportValue(1_450_000, 'USD', 'en', 'compact')).toBe('$1.45M');
        expect(formatReportValue(1_020_000, 'USD', 'en', 'compact')).toBe('$1.02M');
        expect(formatReportValue(1_733_430_000, 'JPY', 'en', 'compact')).toBe('¥1.73B');
        expect(formatReportValue(-1_450_000, 'USD', 'en', 'compact')).toBe('-$1.45M');
        expect(formatReportValue(1_450_000, 'USD', 'ja', 'compact')).toBe('$145万');
        expect(formatReportValue(1_020_000, 'USD', 'ja', 'compact')).toBe('$102万');
        expect(formatReportValue(1_733_430_000, 'JPY', 'ja', 'compact')).toBe('￥17.33億');
    });

    it('retains precision for extended currency codes and amounts below the compact threshold', () => {
        expect(formatReportValue(10_000.25, 'USDT', 'en')).toBe('10,000.25 USDT');
        expect(formatReportValue(9_999.99, 'USD', 'en', 'compact')).toBe('$9,999.99');
        expect(formatReportValue(10_000.25, 'USD', 'en', 'compact')).toBe('$10K');
    });

    it('preserves missing values, percentages, and count formatting', () => {
        expect(formatReportValue(null, 'USD', 'en')).toBe('-');
        expect(formatReportValue(42.25, 'percent', 'en')).toBe('42.3%');
        expect(formatReportValue(42.25, '%', 'ja')).toBe('42.3%');
        expect(formatReportValue(10_500, null, 'en')).toBe('10.5K');
    });
});

describe.each(['en', 'ja'] as const)('mixed-currency report display in %s', (locale) => {
    it.each(['table', 'kpi'] as const)('retains exact current and prior amounts in a single-currency %s', (chartType) => {
        const widget = mixedWidget(chartType);
        const markup = renderWidget({
            ...widget,
            unit: 'USD',
            total: 1_450_000.25,
            priorTotal: 1_020_000.75,
            points: widget.points.slice(0, 1),
        }, locale);

        expect(markup).toContain('$1,450,000.25');
        expect(markup).toContain('$1,020,000.75');
        expect(markup).not.toContain(catalogues[locale].Reports.document.mixedCurrencyChartUnavailable);
    });

    it.each(['bar', 'line-area', 'funnel', 'donut'] as const)('explains the %s fallback and retains both currency partitions', (chartType) => {
        const markup = renderWidget(mixedWidget(chartType), locale);

        expect(markup).toContain(catalogues[locale].Reports.document.mixedCurrencyChartUnavailable);
        expect(markup).toContain('<table');
        expect(markup).not.toContain('data-slot="chart"');
        expect(markup).toContain('USD · Owner');
        expect(markup).toContain('JPY · Owner');
        expect(markup).toContain('$1,450,000.25');
        expect(markup).toContain('$1,020,000.75');
        expect(markup).toContain(locale === 'ja' ? '￥1,733,430,000' : '¥1,733,430,000');
    });

    it.each(['table', 'kpi'] as const)('does not describe an intentional %s as an unavailable chart', (chartType) => {
        const markup = renderWidget(mixedWidget(chartType), locale);

        expect(markup).not.toContain(catalogues[locale].Reports.document.mixedCurrencyChartUnavailable);
        expect(markup).toContain('$1,450,000.25');
    });

    it('preserves the no-data state', () => {
        const markup = renderWidget({ ...mixedWidget('bar'), points: [] }, locale);

        expect(markup).toContain(catalogues[locale].Reports.document.noData);
        expect(markup).not.toContain(catalogues[locale].Reports.document.mixedCurrencyChartUnavailable);
    });
});
