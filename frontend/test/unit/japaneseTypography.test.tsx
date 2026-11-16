/** @vitest-environment jsdom */
import { readFileSync } from 'node:fs';
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import SummaryTile from '@/app/components/SummaryTile';
import InfoRow from '@/app/components/me/InfoRow';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import { NowProvider } from '@/app/hooks/useNow';
import { formatCompactCurrency } from '@/app/lib/utils';
import ja from '@/messages/ja/dashboard.json';

vi.mock('@/components/ui/chart', () => ({
    ChartContainer: () => null,
    ChartTooltip: () => null,
    ChartTooltipContent: () => null,
}));
afterEach(cleanup);

describe('JA-24 Japanese line breaking', () => {
    it('inherits Japanese breaking rules from the active document language', () => {
        const css = readFileSync('app/globals.css', 'utf8');
        const layout = readFileSync('app/layout.tsx', 'utf8');
        expect(layout).toContain('lang={locale}');
        const declarations = /body:lang\(ja\)\s*\{([^}]+)\}/.exec(css)?.[1];
        expect(declarations).toMatch(/word-break:\s*keep-all\s*;/);
        expect(declarations).toMatch(/line-break:\s*strict\s*;/);
        expect(declarations).toMatch(/overflow-wrap:\s*anywhere\s*;/);
    });

    it('keeps Japanese amount-plus-unit values together in the pipeline and Deals tiles', () => {
        const amount = 21_050_000_000;
        const value = formatCompactCurrency(amount, 'JPY', 'ja');
        render(<NextIntlClientProvider locale="ja" messages={ja} timeZone="UTC">
            <NowProvider value={Date.UTC(2026, 10, 15)}>
                <PipelineChart series={{ closed: [], projected: [] }} openValue={amount} currency="JPY" range="90d" />
                <SummaryTile label={ja.DashboardPipelineChart.projections} value={value} />
            </NowProvider>
        </NextIntlClientProvider>);
        expect(value).toBe('￥210.5億');
        const values = screen.getAllByText(value);
        expect(values).toHaveLength(2);
        for (const element of values) expect(element.classList.contains('whitespace-nowrap')).toBe(true);
    });

    it('keeps formatted record date facts together without changing ordinary text wrapping', () => {
        const { container } = render(<dl>
            <InfoRow label="日付" value="2026年12月25日" valueClassName="whitespace-nowrap" />
            <InfoRow label="会社" value="株式会社つながり" />
        </dl>);
        const values = container.querySelectorAll('dd');
        expect(values[0].classList.contains('whitespace-nowrap')).toBe(true);
        expect(values[1].classList.contains('whitespace-nowrap')).toBe(false);
    });
});
