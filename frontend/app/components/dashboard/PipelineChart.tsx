'use client';

import * as React from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts';

import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type Deal } from '@/app/lib/types';
import { formatCompactCurrency, parseMysqlDateTime, pickDominantCurrency } from '@/app/lib/utils';

//originally 6, but i think 12 is more realistic/ helpful
const MONTHS_AHEAD = 12;

type Bucket = { key: string; label: string; value: number };

function buildBuckets(deals: Deal[], now: number, locale: string): Bucket[] {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);

    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();

    // en = Jan, Feb, etc. ja = 1月, 2月, etc.
    const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short' });

    for (let i = 0; i < MONTHS_AHEAD; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);
        buckets.push({ key, label: monthLabel.format(d), value: 0 });
    }

    for (const deal of deals) {
        if (deal.closedAt) continue;
        const t = parseMysqlDateTime(deal.expectedCloseDate);
        if (!Number.isFinite(t)) continue;
        const d = new Date(t);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        const idx = keyToIndex.get(key);
        if (idx === undefined) continue;
        buckets[idx].value += deal.value ?? 0;
    }

    return buckets;
}

export default function PipelineChart({ deals }: { deals: Deal[] }) {
    const t = useTranslations('DashboardPipelineChart');
    const locale = useLocale();
    const now = React.useMemo(() => Date.now(), []);
    const currency = React.useMemo(() => pickDominantCurrency(deals), [deals]);
    const dealsInCurrency = React.useMemo(
        () => deals.filter((d) => (d.currency || 'USD') === currency),
        [deals, currency],
    );
    const openDeals = React.useMemo(
        () => dealsInCurrency.filter((d) => !d.closedAt),
        [dealsInCurrency],
    );
    const data = React.useMemo(() => buildBuckets(dealsInCurrency, now, locale), [dealsInCurrency, now, locale]);

    const pipelineValue = openDeals.reduce((sum, d) => sum + (d.value ?? 0), 0);
    const scheduledValue = data.reduce((sum, b) => sum + b.value, 0);

    const chartConfig = {
        value: {
            label: t('pipelineLabel'),
            color: 'var(--color-chart-3)',
        },
    } satisfies ChartConfig;

    return (
        <div className="flex h-full flex-col rounded-2xl bg-white p-6 ring-1 ring-black/5">
            <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {t('activePipeline')}
            </span>
            <span className="mt-3 text-5xl leading-none text-black tabular-nums">
                {formatCompactCurrency(pipelineValue, currency, locale)}
            </span>
            <p className="mt-2 text-sm text-neutral-500">
                {t('summary', {
                    openCount: openDeals.length,
                    scheduled: formatCompactCurrency(scheduledValue, currency, locale),
                    months: MONTHS_AHEAD,
                })}
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
                                formatCompactCurrency(v, currency, locale)
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
                                                locale,
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