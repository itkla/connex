'use client';

import * as React from 'react';
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import {
    Tooltip,
    TooltipContent,
    TooltipProvider,
    TooltipTrigger,
} from '@/components/ui/tooltip';
import { type DealMonthTotal, type DealRevenueSeries } from '@/app/lib/types';
import { cn } from '@/lib/utils';
import { formatCompactCurrency } from '@/app/lib/utils';
import { type RollingRangeKey } from '@/app/components/overview/analytics/metrics';

const MIN_MONTHS_FORWARD = 3;
const MAX_MONTHS_FORWARD = 12;
const MONTHS_BACK: Record<RollingRangeKey, number> = { '30d': 4, '90d': 7, '12m': 12 };

type Metric = 'profit' | 'projections';
type Bucket = { key: string; label: string; profit: number; projections: number };

const METRICS: readonly Metric[] = ['profit', 'projections'];

function forwardHorizon(series: DealRevenueSeries, now: number): number {
    const reference = new Date(now);
    const base = reference.getFullYear() * 12 + reference.getMonth();
    let furthest = MIN_MONTHS_FORWARD;
    for (const point of [...series.closed, ...series.projected]) {
        const offset = point.year * 12 + (point.month - 1) - base;
        if (offset > furthest) furthest = offset;
    }
    return Math.min(MAX_MONTHS_FORWARD, Math.max(MIN_MONTHS_FORWARD, furthest));
}

function buildBuckets(series: DealRevenueSeries, now: number, locale: string, range: RollingRangeKey): Bucket[] {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);
    start.setMonth(start.getMonth() - (MONTHS_BACK[range] - 1));

    const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short' });
    const monthYearLabel = new Intl.DateTimeFormat(locale, { month: 'short', year: '2-digit' });
    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();
    const total = MONTHS_BACK[range] + forwardHorizon(series, now);

    for (let i = 0; i < total; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);
        const label = d.getMonth() === 0 || i === 0 ? monthYearLabel.format(d) : monthLabel.format(d);
        buckets.push({ key, label, profit: 0, projections: 0 });
    }

    const bucketKeyOf = (point: DealMonthTotal) => `${point.year}-${point.month - 1}`;
    for (const point of series.closed) {
        const idx = keyToIndex.get(bucketKeyOf(point));
        if (idx !== undefined) buckets[idx].profit += point.total;
    }
    for (const point of series.projected) {
        const idx = keyToIndex.get(bucketKeyOf(point));
        if (idx !== undefined) buckets[idx].projections += point.total;
    }

    return buckets;
}

/**
 * Active-pipeline trend — realized profit (won-by-close-month) vs projected value
 * (by expected-close-month) — from the server-computed {@link DealRevenueSeries}. The series is
 * aggregated server-side over all deals in the dashboard's currency; {@code range} only sizes the
 * historical window shown.
 */
export default function PipelineChart({
    series,
    currency,
    range,
}: {
    series: DealRevenueSeries;
    currency: string;
    range: RollingRangeKey;
}) {
    const t = useTranslations('DashboardPipelineChart');
    const locale = useLocale();
    const [active, setActive] = React.useState<Metric>('projections');
    const [now] = React.useState(() => Date.now());

    const data = React.useMemo(
        () => buildBuckets(series, now, locale, range),
        [series, now, locale, range],
    );
    const labelByKey = React.useMemo(() => {
        const map = new Map<string, string>();
        for (const b of data) map.set(b.key, b.label);
        return map;
    }, [data]);
    const totals = React.useMemo(
        () => ({
            profit: data.reduce((sum, b) => sum + b.profit, 0),
            projections: data.reduce((sum, b) => sum + b.projections, 0),
        }),
        [data],
    );

    const chartConfig = {
        profit: { label: t('profit'), color: 'var(--color-brand)' },
        projections: { label: t('projections'), color: 'var(--color-brand)' },
    } satisfies ChartConfig;

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex flex-col border-b border-border sm:flex-row sm:items-stretch">
                <div className="flex flex-1 flex-col justify-center gap-1 px-6 py-5">
                    <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                            {t('activePipeline')}
                        </span>
                        <span className="rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground ring-1 ring-border">
                            {currency}
                        </span>
                    </div>
                    <span className="text-sm text-muted-foreground">{t('chartDescription')}</span>
                </div>
                <TooltipProvider delayDuration={150}>
                    <div className="flex">
                        {METRICS.map((key) => {
                            const isActive = active === key;
                            return (
                                <Tooltip key={key}>
                                    <TooltipTrigger asChild>
                                        <button
                                            type="button"
                                            data-active={isActive}
                                            aria-pressed={isActive}
                                            onClick={() => setActive(key)}
                                            className={cn(
                                                'flex flex-1 flex-col justify-center gap-1 border-t border-border px-6 py-4 text-left transition-colors hover:bg-muted sm:border-t-0 sm:border-l sm:px-7',
                                                isActive && 'bg-brand-light/30',
                                            )}
                                        >
                                            <span className="text-xs font-medium tracking-wide text-muted-foreground">
                                                {chartConfig[key].label}
                                            </span>
                                            <span
                                                className={cn(
                                                    'text-xl leading-none font-semibold tabular-nums',
                                                    isActive ? 'text-brand-dark' : 'text-foreground',
                                                )}
                                            >
                                                {formatCompactCurrency(totals[key], currency, locale)}
                                            </span>
                                        </button>
                                    </TooltipTrigger>
                                    <TooltipContent>{t(`${key}Tooltip`)}</TooltipContent>
                                </Tooltip>
                            );
                        })}
                    </div>
                </TooltipProvider>
            </div>

            <div className="flex-1 px-2 pt-4 pb-2 sm:px-4">
                <ChartContainer config={chartConfig} className="aspect-auto h-60 w-full">
                    <AreaChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
                        <defs>
                            <linearGradient id="fill-pipeline" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="var(--color-brand)" stopOpacity={0.25} />
                                <stop offset="100%" stopColor="var(--color-brand)" stopOpacity={0} />
                            </linearGradient>
                        </defs>
                        <CartesianGrid vertical={false} strokeDasharray="3 3" stroke="var(--chart-grid)" />
                        <XAxis
                            dataKey="key"
                            tickFormatter={(value: string) => labelByKey.get(value) ?? value}
                            tickLine={false}
                            axisLine={false}
                            tickMargin={8}
                            minTickGap={24}
                            tick={{ fill: 'var(--muted-foreground)' }}
                        />
                        <YAxis
                            tickLine={false}
                            axisLine={false}
                            tickMargin={4}
                            width={48}
                            tick={{ fill: 'var(--muted-foreground)' }}
                            tickFormatter={(v: number) => formatCompactCurrency(v, currency, locale)}
                        />
                        <ChartTooltip
                            cursor={false}
                            content={
                                <ChartTooltipContent
                                    indicator="dot"
                                    formatter={(value) => (
                                        <div className="flex w-full items-center gap-2">
                                            <span
                                                className="size-2.5 shrink-0 rounded-[2px]"
                                                style={{ backgroundColor: 'var(--color-brand)' }}
                                            />
                                            <span className="text-muted-foreground">
                                                {chartConfig[active].label}
                                            </span>
                                            <span className="ml-auto font-mono font-medium text-foreground tabular-nums">
                                                {formatCompactCurrency(Number(value), currency, locale)}
                                            </span>
                                        </div>
                                    )}
                                />
                            }
                        />
                        <Area
                            dataKey={active}
                            type="monotone"
                            fill="url(#fill-pipeline)"
                            stroke="var(--color-brand)"
                            strokeWidth={2}
                            isAnimationActive
                            animationDuration={500}
                            animationEasing="ease-out"
                        />
                    </AreaChart>
                </ChartContainer>
            </div>
        </div>
    );
}
