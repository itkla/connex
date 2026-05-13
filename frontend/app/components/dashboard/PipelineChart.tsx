'use client';

import * as React from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts';

import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type Deal } from '@/app/lib/api';
import { formatCompactCurrency, timeOf } from '@/app/lib/utils';

//originally 6, but i think 12 is more realistic/ helpful
const MONTHS_AHEAD = 12;

const chartConfig = {
    value: {
        label: 'Pipeline',
        color: 'var(--color-chart-3)',
    },
} satisfies ChartConfig;

type Bucket = { key: string; label: string; value: number };

function buildBuckets(deals: Deal[], now: number): Bucket[] {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);

    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();

    // en = Jan, Feb, etc. ja = 1月, 2月, etc. right now during dev, im sticking with en so i can move fast 
    const monthLabel = new Intl.DateTimeFormat('en', { month: 'short' });

    for (let i = 0; i < MONTHS_AHEAD; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);
        buckets.push({ key, label: monthLabel.format(d), value: 0 });
    }

    for (const deal of deals) {
        if (deal.closedAt) continue;
        const t = timeOf(deal.expectedCloseDate);
        if (!t) continue; // if the deal has no expected close date and it's not closed, skip it
        const d = new Date(t);
        const key = `${d.getFullYear()}-${d.getMonth()}`; // chart key accepts yyyy-mm, so we need to convert the date to a string
        const idx = keyToIndex.get(key);
        if (idx === undefined) continue;
        buckets[idx].value += deal.value ?? 0;
    }

    return buckets;
}

export default function PipelineChart({ deals }: { deals: Deal[] }) {
    const now = React.useMemo(() => Date.now(), []);
    const openDeals = React.useMemo(
        () => deals.filter((d) => !d.closedAt),
        [deals],
    );
    const data = React.useMemo(() => buildBuckets(deals, now), [deals, now]);

    const pipelineValue = openDeals.reduce((sum, d) => sum + (d.value ?? 0), 0);
    const currency = openDeals.find((d) => d.currency)?.currency ?? 'USD';
    const scheduledValue = data.reduce((sum, b) => sum + b.value, 0);

    return (
        <div className="flex h-full flex-col rounded-2xl bg-white p-6 ring-1 ring-black/5">
            <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                Active pipeline
            </span>
            <span className="mt-3 text-5xl leading-none text-black tabular-nums">
                {formatCompactCurrency(pipelineValue, currency)}
            </span>
            <p className="mt-2 text-sm text-neutral-500">
                {openDeals.length} open · {formatCompactCurrency(scheduledValue, currency)} scheduled in next {MONTHS_AHEAD} months
            </p>
            <div className="mt-6 flex-1 border-t border-neutral-200 pt-4">
                <ChartContainer
                    config={chartConfig}
                    className="aspect-auto h-44 w-full"
                >
                    <BarChart
                        data={data}
                        margin={{ top: 4, right: 4, left: -16, bottom: 0 }}
                    >
                        <CartesianGrid vertical={false} strokeDasharray="3 3" />
                        <XAxis
                            dataKey="label"
                            tickLine={false}
                            axisLine={false}
                            tickMargin={8}
                        />
                        <YAxis
                            tickLine={false}
                            axisLine={false}
                            tickMargin={4}
                            width={48}
                            tickFormatter={(v: number) =>
                                formatCompactCurrency(v, currency)
                            }
                        />
                        <ChartTooltip
                            cursor={false}
                            content={
                                <ChartTooltipContent
                                    formatter={(value) => (
                                        <span className="font-mono font-medium text-foreground tabular-nums">
                                            {formatCompactCurrency(
                                                Number(value),
                                                currency,
                                            )}
                                        </span>
                                    )}
                                />
                            }
                        />
                        <Bar
                            dataKey="value"
                            fill="var(--color-value)"
                            radius={[4, 4, 0, 0]}
                        />
                    </BarChart>
                </ChartContainer>
            </div>
        </div>
    );
}