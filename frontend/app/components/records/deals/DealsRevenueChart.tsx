'use client';

import * as React from 'react';
import { CartesianGrid, Line, LineChart, ReferenceLine, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';

import {
    ChartContainer,
    ChartLegend,
    ChartLegendContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type Deal } from '@/app/lib/types';
import { formatCompactCurrency, timeOf } from '@/app/lib/utils';

const MONTHS_BACK = 6;
const MONTHS_FORWARD = 6;

const chartConfig = {
    closed: { label: 'Actual revenue', color: 'var(--color-brand)' },
    projected: { label: 'Projected revenue', color: 'var(--color-chart-2)' },
} satisfies ChartConfig;

type Bucket = { key: string; label: string; closed: number; projected: number };

function buildBuckets(deals: Deal[], now: number) {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);
    start.setMonth(start.getMonth() - MONTHS_BACK);

    const monthLabel = new Intl.DateTimeFormat('en', { month: 'short' });
    const monthYearLabel = new Intl.DateTimeFormat('en', { month: 'short', year: '2-digit' });
    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();

    const total = MONTHS_BACK + MONTHS_FORWARD + 1;
    for (let i = 0; i < total; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);

        // display as Month Year
        const label = d.getMonth() === 0 || i === 0 ? monthYearLabel.format(d) : monthLabel.format(d);
        
        buckets.push({ key, label, closed: 0, projected: 0 });
    }

    const today = new Date(now);
    const todayKey = `${today.getFullYear()}-${today.getMonth()}`;
    const todayLabel = buckets[keyToIndex.get(todayKey) ?? -1]?.label ?? null;

    let currency = 'USD';
    for (const deal of deals) {
        if (deal.currency) currency = deal.currency;
        const closedAt = timeOf(deal.closedAt);
        const expected = timeOf(deal.expectedCloseDate);

        if (closedAt > 0 && closedAt <= now) {
            const d = new Date(closedAt);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].closed += deal.actualValue ?? 0;
        }
        if (expected > 0) {
            const d = new Date(expected);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].projected += deal.value ?? 0;
        }
    }

    return { data: buckets, todayLabel, currency };
}

export default function DealsRevenueChart({ deals }: { deals: Deal[] }) {
    const now = React.useMemo(() => Date.now(), []);
    const { data, todayLabel, currency } = React.useMemo(() => buildBuckets(deals, now), [deals, now]);

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <LineChart data={data} margin={{ top: 12, right: 12, left: -8, bottom: 0 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
                    tick={{ fontSize: 11, fill: '#737373' }}
                />
                <YAxis
                    tickLine={false}
                    axisLine={false}
                    tickMargin={4}
                    width={56}
                    tick={{ fontSize: 11, fill: '#737373' }}
                    tickFormatter={(v: number) => formatCompactCurrency(v, currency)}
                />
                <RechartsTooltip
                    cursor={{ stroke: 'var(--color-brand)', strokeOpacity: 0.3, strokeWidth: 1 }}
                    content={<RevenueChartTooltip currency={currency} />}
                />
                {todayLabel != null && (
                    <ReferenceLine
                        x={todayLabel}
                        stroke="#a3a3a3"
                        strokeDasharray="4 4"
                        label={{ value: 'Today', position: 'top', fontSize: 10, fill: '#737373' }}
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
}

function RevenueChartTooltip({ active, payload, currency = 'USD' }: RevenueChartTooltipProps) {
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    return (
        <div className="rounded-md bg-white p-2 text-xs ring-1 ring-black/5 shadow-md">
            <div className="font-medium text-neutral-700 mb-1.5">
                {d.label}
            </div>
            <div className="space-y-0.5">
                <div className="flex items-center gap-1.5 text-neutral-600">
                    <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: 'var(--color-chart-2)' }} />
                    Projected · {formatCompactCurrency(d.projected, currency)}
                </div>
                <div className="flex items-center gap-1.5 text-neutral-600">
                    <span className="inline-block size-2 rounded-sm bg-brand" />
                    Actual · {formatCompactCurrency(d.closed, currency)}
                </div>
            </div>
        </div>
    );
}