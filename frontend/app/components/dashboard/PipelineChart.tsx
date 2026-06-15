'use client';

import * as React from 'react';
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronDownIcon } from '@heroicons/react/24/outline';

import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
    Tooltip,
    TooltipContent,
    TooltipProvider,
    TooltipTrigger,
} from '@/components/ui/tooltip';
import { type Deal } from '@/app/lib/types';
import { cn } from '@/lib/utils';
import { formatCompactCurrency, parseMysqlDateTime, pickDominantCurrency } from '@/app/lib/utils';

const MONTHS_BACK = 5;
const MONTHS_TOTAL = 12;

type Metric = 'profit' | 'projections';
type Bucket = { key: string; label: string; profit: number; projections: number };

function buildBuckets(deals: Deal[], now: number, locale: string): Bucket[] {
    const start = new Date(now);
    start.setDate(1);
    start.setHours(0, 0, 0, 0);
    start.setMonth(start.getMonth() - MONTHS_BACK);

    const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short' });
    const buckets: Bucket[] = [];
    const keyToIndex = new Map<string, number>();

    for (let i = 0; i < MONTHS_TOTAL; i++) {
        const d = new Date(start.getFullYear(), start.getMonth() + i, 1);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        keyToIndex.set(key, buckets.length);
        buckets.push({ key, label: monthLabel.format(d), profit: 0, projections: 0 });
    }

    for (const deal of deals) {
        const closed = parseMysqlDateTime(deal.closedAt);
        if (Number.isFinite(closed) && closed <= now) {
            // realized profit, bucketed by when it closed
            const d = new Date(closed);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].profit += deal.actualValue ?? 0;
        } else {
            // open deal, projected by expected close date
            const expected = parseMysqlDateTime(deal.expectedCloseDate);
            if (!Number.isFinite(expected)) continue;
            const d = new Date(expected);
            const idx = keyToIndex.get(`${d.getFullYear()}-${d.getMonth()}`);
            if (idx !== undefined) buckets[idx].projections += deal.value ?? 0;
        }
    }

    return buckets;
}

export default function PipelineChart({ deals }: { deals: Deal[] }) {
    const t = useTranslations('DashboardPipelineChart');
    const locale = useLocale();
    const [active, setActive] = React.useState<Metric>('projections');
    const [selectedCurrency, setSelectedCurrency] = React.useState<string | null>(null);
    const now = React.useMemo(() => new Date().getTime(), []);

    const currencyCounts = React.useMemo(() => {
        const counts = new Map<string, number>();
        for (const d of deals) {
            const c = d.currency || 'USD';
            counts.set(c, (counts.get(c) ?? 0) + 1);
        }
        return counts;
    }, [deals]);
    const dominantCurrency = React.useMemo(() => pickDominantCurrency(deals), [deals]);
    const currency =
        selectedCurrency && currencyCounts.has(selectedCurrency) ? selectedCurrency : dominantCurrency;
    const dealsInCurrency = React.useMemo(
        () => deals.filter((d) => (d.currency || 'USD') === currency),
        [deals, currency],
    );
    const data = React.useMemo(
        () => buildBuckets(dealsInCurrency, now, locale),
        [dealsInCurrency, now, locale],
    );
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

    const metrics: Metric[] = ['profit', 'projections'];

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex flex-col border-b border-border sm:flex-row sm:items-stretch">
                <div className="flex flex-1 flex-col justify-center gap-1 px-6 py-5">
                    <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                            {t('activePipeline')}
                        </span>
                        {currencyCounts.size > 1 ? (
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <button
                                        type="button"
                                        aria-label={t('currency')}
                                        className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-foreground ring-1 ring-border transition hover:bg-muted/60"
                                    >
                                        {currency}
                                        <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                                    </button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                    {Array.from(currencyCounts.entries())
                                        .sort((a, b) => b[1] - a[1])
                                        .map(([code, count]) => (
                                            <DropdownMenuItem
                                                key={code}
                                                onSelect={() => setSelectedCurrency(code)}
                                            >
                                                <span className={code === currency ? 'font-semibold' : ''}>
                                                    {code}
                                                </span>
                                                <span className="ml-auto text-xs tabular-nums text-muted-foreground">
                                                    {count}
                                                </span>
                                            </DropdownMenuItem>
                                        ))}
                                </DropdownMenuContent>
                            </DropdownMenu>
                        ) : (
                            <span className="rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground ring-1 ring-border">
                                {currency}
                            </span>
                        )}
                    </div>
                    <span className="text-sm text-muted-foreground">{t('chartDescription')}</span>
                </div>
                <TooltipProvider delayDuration={150}>
                    <div className="flex">
                        {metrics.map((key) => {
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
                            dataKey="label"
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