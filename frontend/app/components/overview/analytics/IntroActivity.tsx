'use client';

import { autoYAxisWidth, monthAxisTicks } from '@/app/lib/chartAxes';
import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type IntroSuggestion, type IntroductionRecord } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import {
    buildCalendarBuckets,
    formatPeriodTick,
    type AnalyticsWindow,
    type Granularity,
} from '@/app/components/overview/analytics/metrics';

export default function IntroActivity({
    suggestions,
    lineage,
    analyticsWindow,
    granularity,
}: {
    suggestions: IntroSuggestion[];
    lineage: IntroductionRecord[];
    analyticsWindow: AnalyticsWindow;
    granularity: Granularity;
}) {
    const t = useTranslations('AnalyticsIntros');
    const locale = useLocale();

    const { data, made } = useMemo(() => {
        const buckets = buildCalendarBuckets(analyticsWindow, granularity, locale);
        const rows = buckets.map((bucket, index) => {
            const date = new Date(bucket.start);
            const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
            return {
                key,
                label: granularity === 'month' ? formatPeriodTick(key, granularity, locale, index === 0 || date.getMonth() === 0) : bucket.label,
                made: 0,
            };
        });
        let total = 0;
        for (const record of lineage) {
            const ts = parseMysqlDateTime(record.introducedAt);
            if (!Number.isFinite(ts)) continue;
            const idx = buckets.findIndex((bucket) => ts >= bucket.start && ts < bucket.end);
            if (idx < 0) continue;
            rows[idx].made += 1;
            total += 1;
        }
        return { data: rows, made: total };
    }, [lineage, analyticsWindow, granularity, locale]);

    const chartConfig = useMemo<ChartConfig>(
        () => ({ made: { label: t('made'), color: 'var(--color-brand)' } }),
        [t],
    );

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5 flex flex-wrap items-end gap-x-8 gap-y-3">
                <div>
                    <div className="text-3xl leading-none text-foreground tabular-nums">{suggestions.length}</div>
                    <p className="mt-1.5 text-sm text-muted-foreground">{t('opportunities')}</p>
                </div>
                <div>
                    <div className="text-3xl leading-none text-foreground tabular-nums">{made}</div>
                    <p className="mt-1.5 text-sm text-muted-foreground">{t('madeInPeriod')}</p>
                </div>
            </div>

            {made === 0 ? (
                <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
                    {t('emptyMade')}
                </div>
            ) : (
                <ChartContainer config={chartConfig} className="aspect-auto h-40 w-full">
                    <BarChart data={data} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
                        <CartesianGrid vertical={false} strokeDasharray="3 3" />
                        <XAxis
                            dataKey="key"
                            padding={{ left: 12, right: 32 }}
                            tickFormatter={(value: string) => data.find((row) => row.key === value)?.label ?? value}
                            ticks={granularity === 'month' ? monthAxisTicks(data.map((row) => row.key)) : undefined}
                            interval={granularity === 'month' ? 0 : 'preserveStartEnd'}
                            tickLine={false}
                            axisLine={false}
                            tickMargin={10}
                            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                        />
                        <YAxis
                            tickLine={false}
                            axisLine={false}
                            {...autoYAxisWidth()}
                            allowDecimals={false}
                            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                        />
                        <ChartTooltip
                            cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                            content={<ChartTooltipContent labelFormatter={(value) => data.find((row) => row.key === value)?.label ?? String(value)} />}
                        />
                        <Bar dataKey="made" fill="var(--color-brand)" radius={[4, 4, 0, 0]} />
                    </BarChart>
                </ChartContainer>
            )}
        </div>
    );
}
