'use client';

import * as React from 'react';
import { CartesianGrid, Line, LineChart, ReferenceLine, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import {
    ChartContainer,
    ChartLegend,
    ChartLegendContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type DealRevenueSeries } from '@/app/lib/types';
import { formatCompactCurrency, yearMonthInTimezone } from '@/app/lib/utils';

const MONTHS_BACK = 6;
const MONTHS_FORWARD = 6;

type Bucket = { key: string; label: string; closed: number; projected: number };

function buildBuckets(series: DealRevenueSeries, now: number, locale: string, timezone: string) {
    const current = yearMonthInTimezone(now, timezone);
    const currentIndex = current.year * 12 + current.month - 1;
    const startIndex = currentIndex - MONTHS_BACK;

    const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' });
    const monthYearLabel = new Intl.DateTimeFormat(locale, { month: 'short', year: '2-digit', timeZone: 'UTC' });
    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();

    const total = MONTHS_BACK + MONTHS_FORWARD + 1;
    for (let i = 0; i < total; i++) {
        const monthIndex = startIndex + i;
        const year = Math.floor(monthIndex / 12);
        const month = ((monthIndex % 12) + 12) % 12;
        const d = new Date(Date.UTC(year, month, 1));
        const key = `${year}-${month}`;
        keyToIndex.set(key, buckets.length);

        const label = month === 0 || i === 0 ? monthYearLabel.format(d) : monthLabel.format(d);

        buckets.push({ key, label, closed: 0, projected: 0 });
    }

    const todayKey = `${current.year}-${current.month - 1}`;
    const todayLabel = buckets[keyToIndex.get(todayKey) ?? -1]?.label ?? null;

    for (const point of series.closed) {
        const idx = keyToIndex.get(`${point.year}-${point.month - 1}`);
        if (idx !== undefined) buckets[idx].closed += point.total;
    }
    for (const point of series.projected) {
        const idx = keyToIndex.get(`${point.year}-${point.month - 1}`);
        if (idx !== undefined) buckets[idx].projected += point.total;
    }

    return { data: buckets, todayLabel };
}

export default function DealsRevenueChart({
    series,
    currency,
    timezone,
}: {
    series: DealRevenueSeries;
    currency: string;
    timezone: string;
}) {
    const t = useTranslations('DealsRevenueChart');
    const locale = useLocale();
    const [now] = React.useState(() => Date.now());
    const { data, todayLabel } = React.useMemo(
        () => buildBuckets(series, now, locale, timezone),
        [series, now, locale, timezone],
    );
    const chartConfig = React.useMemo(
        () =>
            ({
                closed: { label: t('actualRevenue'), color: 'var(--color-brand)' },
                projected: { label: t('projectedRevenue'), color: 'var(--muted-foreground)' },
            }) satisfies ChartConfig,
        [t],
    );

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <LineChart data={data} margin={{ top: 12, right: 12, left: -8, bottom: 0 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
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
                    cursor={{ stroke: 'var(--color-brand)', strokeOpacity: 0.3, strokeWidth: 1 }}
                    content={
                        <RevenueChartTooltip
                            currency={currency}
                            projectedLabel={(amount) => t('projectedAmount', { amount })}
                            actualLabel={(amount) => t('actualAmount', { amount })}
                        />
                    }
                />
                {todayLabel != null && (
                    <ReferenceLine
                        x={todayLabel}
                        stroke="var(--chart-grid)"
                        strokeDasharray="4 4"
                        label={{ value: t('today'), position: 'top', fontSize: 10, fill: 'var(--chart-axis)' }}
                    />
                )}
                <Line
                    type="monotone"
                    dataKey="closed"
                    stroke="var(--color-closed)"
                    strokeWidth={2}
                    dot={{ r: 2.5, fill: 'var(--color-closed)', stroke: 'var(--color-closed)' }}
                    isAnimationActive={false}
                />
                <Line
                    type="monotone"
                    dataKey="projected"
                    stroke="var(--color-projected)"
                    strokeWidth={2}
                    strokeDasharray="5 4"
                    dot={{ r: 2.5, fill: 'var(--color-projected)', stroke: 'var(--color-projected)' }}
                    isAnimationActive={false}
                />
                <ChartLegend content={<ChartLegendContent />} />
            </LineChart>
        </ChartContainer>
    );
}

interface RevenueChartTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: Bucket }>;
    currency?: string;
    projectedLabel?: (amount: string) => string;
    actualLabel?: (amount: string) => string;
}

function RevenueChartTooltip({ active, payload, currency = 'USD', projectedLabel, actualLabel }: RevenueChartTooltipProps) {
    const locale = useLocale();
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    const projectedAmount = formatCompactCurrency(d.projected, currency, locale);
    const actualAmount = formatCompactCurrency(d.closed, currency, locale);
    return (
        <div className="rounded-md bg-popover text-popover-foreground p-2 text-xs border border-border shadow-md">
            <div className="font-medium text-popover-foreground mb-1.5">
                {d.label}
            </div>
            <div className="space-y-0.5">
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: 'var(--muted-foreground)' }} />
                    {projectedLabel ? projectedLabel(projectedAmount) : `Projected · ${projectedAmount}`}
                </div>
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm bg-brand" />
                    {actualLabel ? actualLabel(actualAmount) : `Actual · ${actualAmount}`}
                </div>
            </div>
        </div>
    );
}
