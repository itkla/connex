'use client';

import { useMemo, useState } from 'react';
import { Area, AreaChart, CartesianGrid, Line, ReferenceLine, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import { ChartContainer, type ChartConfig } from '@/components/ui/chart';
import { type Deal } from '@/app/lib/types';
import { formatCompactCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { type RangeKey } from '@/app/components/overview/analytics/metrics';

const MIN_MONTHS_FORWARD = 3;
const MAX_MONTHS_FORWARD = 12;
const MONTHS_BACK: Record<RangeKey, number> = { '30d': 4, '90d': 7, '12m': 12 };

type Bucket = { key: string; label: string; won: number; projected: number };

function forwardHorizon(deals: Deal[], now: number): number {
    const reference = new Date(now);
    const base = reference.getFullYear() * 12 + reference.getMonth();
    let furthest = MIN_MONTHS_FORWARD;
    // find the furthest expected close date
    for (const deal of deals) {
        const expected = parseMysqlDateTime(deal.expectedCloseDate);
        if (!Number.isFinite(expected)) continue;
        const d = new Date(expected);
        const offset = d.getFullYear() * 12 + d.getMonth() - base;
        if (offset > furthest) furthest = offset;
    }
    return Math.min(MAX_MONTHS_FORWARD, Math.max(MIN_MONTHS_FORWARD, furthest));
}

function buildBuckets(deals: Deal[], now: number, locale: string, range: RangeKey) {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);
    start.setMonth(start.getMonth() - (MONTHS_BACK[range] - 1));

    const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short' });
    const monthYearLabel = new Intl.DateTimeFormat(locale, { month: 'short', year: '2-digit' });
    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();
    const total = MONTHS_BACK[range] + forwardHorizon(deals, now);

    // build buckets
    for (let i = 0; i < total; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);
        const label = d.getMonth() === 0 || i === 0 ? monthYearLabel.format(d) : monthLabel.format(d);
        buckets.push({ key, label, won: 0, projected: 0 });
    }

    // add today label
    const today = new Date(now);
    const todayLabel = buckets[keyToIndex.get(`${today.getFullYear()}-${today.getMonth()}`) ?? -1]?.label ?? null;

    for (const deal of deals) {
        const closed = parseMysqlDateTime(deal.closedAt);
        const expected = parseMysqlDateTime(deal.expectedCloseDate);
        const isClosed = Number.isFinite(closed) && closed <= now;

        // add won value to the bucket
        if (isClosed) {
            const bucketTime = Number.isFinite(expected) ? expected : closed;
            const d = new Date(bucketTime);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].won += deal.actualValue ?? 0;
        }
        // add projected value to the bucket
        if (Number.isFinite(expected)) {
            const d = new Date(expected);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].projected += deal.value ?? 0;
        }
    }

    return { data: buckets, todayLabel };
}

export default function RevenueTrend({
    deals,
    currency,
    range,
}: {
    deals: Deal[];
    currency: string;
    range: RangeKey;
}) {
    const t = useTranslations('AnalyticsRevenue');
    const locale = useLocale();
    const [now] = useState(() => Date.now());
    const { data, todayLabel } = useMemo(
        () => buildBuckets(deals, now, locale, range),
        [deals, now, locale, range],
    );

    const hasData = data.some((b) => b.won > 0 || b.projected > 0);

    const chartConfig = {
        won: { label: t('actual'), color: 'var(--color-brand)' },
        projected: { label: t('projected'), color: 'var(--muted-foreground)' },
    } satisfies ChartConfig;

    if (!hasData) {
        return (
            <div className="flex h-72 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>
        );
    }

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-72 w-full">
            <AreaChart data={data} margin={{ top: 12, right: 8, left: -8, bottom: 0 }}>
                <defs>
                    <linearGradient id="revenue-won-fill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="var(--color-brand)" stopOpacity={0.22} />
                        <stop offset="100%" stopColor="var(--color-brand)" stopOpacity={0} />
                    </linearGradient>
                </defs>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    tickMargin={10}
                    tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                />
                <YAxis
                    tickLine={false}
                    axisLine={false}
                    tickMargin={4}
                    width={56}
                    tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                    tickFormatter={(v: number) => formatCompactCurrency(v, currency, locale)}
                />
                <RechartsTooltip
                    cursor={{ stroke: 'var(--color-brand)', strokeOpacity: 0.25, strokeWidth: 1 }}
                    content={
                        <RevenueTooltip
                            currency={currency}
                            wonLabel={(amount) => t('actualAmount', { amount })}
                            projectedLabel={(amount) => t('projectedAmount', { amount })}
                        />
                    }
                />
                {todayLabel != null && (
                    <ReferenceLine
                        x={todayLabel}
                        stroke="var(--chart-grid)"
                        strokeDasharray="4 4"
                        label={{ value: t('today'), position: 'top', fontSize: 10, fill: 'var(--muted-foreground)' }}
                    />
                )}
                <Area
                    type="monotone"
                    dataKey="won"
                    stroke="var(--color-brand)"
                    strokeWidth={2}
                    fill="url(#revenue-won-fill)"
                    dot={false}
                    activeDot={{ r: 3.5, strokeWidth: 0 }}
                    isAnimationActive={false}
                />
                <Line
                    type="monotone"
                    dataKey="projected"
                    stroke="var(--muted-foreground)"
                    strokeWidth={2}
                    strokeDasharray="5 4"
                    dot={false}
                    activeDot={{ r: 3, strokeWidth: 0 }}
                    isAnimationActive={false}
                />
            </AreaChart>
        </ChartContainer>
    );
}

function RevenueTooltip({
    active,
    payload,
    currency,
    wonLabel,
    projectedLabel,
}: {
    active?: boolean;
    payload?: Array<{ payload: Bucket }>;
    currency: string;
    wonLabel: (amount: string) => string;
    projectedLabel: (amount: string) => string;
}) {
    const locale = useLocale();
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    return (
        <div className="rounded-md bg-popover p-2 text-xs text-popover-foreground border border-border shadow-md">
            <div className="mb-1.5 font-medium text-foreground">{d.label}</div>
            <div className="space-y-0.5">
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm bg-brand" />
                    {wonLabel(formatCompactCurrency(d.won, currency, locale))}
                </div>
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: 'var(--muted-foreground)' }} />
                    {projectedLabel(formatCompactCurrency(d.projected, currency, locale))}
                </div>
            </div>
        </div>
    );
}