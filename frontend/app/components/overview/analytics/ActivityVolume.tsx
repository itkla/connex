'use client';

import { autoYAxisWidth, monthAxisTicks } from '@/app/lib/chartAxes';
import { useLiveNow } from '@/app/hooks/useNow';
import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import { useTimeZone, useLocale, useTranslations } from 'next-intl';

import {
    ChartContainer,
    ChartLegend,
    ChartLegendContent,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';
import { type ActivityVolumeBucket } from '@/app/lib/types';
import {
    ACTIVITY_COLORS,
    ACTIVITY_TYPES,
    buildTimeBuckets,
    formatPeriodTick,
    formatPeriodTooltipDate,
    type ActivityType,
    type Granularity,
    type RollingRangeKey,
} from './metrics';

type Row = { key: string; label: string; tooltipLabel: string } & Record<ActivityType, number>;

/**
 * Stacked activity-volume chart. Consumes server-aggregated {@link ActivityVolumeBucket}s
 * (oldest→newest by {@code bucketIndex}) and renders one bar per time bucket. On the
 * calendar-aligned path ({@code granularity} set) labels come from each bucket's
 * server-provided {@code periodStart}; on the legacy rolling path {@code range} drives
 * approximate labels via {@link buildTimeBuckets}.
 */
export default function ActivityVolume({
    buckets,
    range,
    granularity,
}: {
    buckets: ActivityVolumeBucket[];
    range?: RollingRangeKey;
    granularity?: Granularity;
}) {
    const t = useTranslations('AnalyticsActivity');
    const tPage = useTranslations('AnalyticsPage');
    const locale = useLocale();
    const now = useLiveNow();
    const timeZone = useTimeZone() ?? 'UTC';

    const { data, activeTypes, tooltipLabels } = useMemo(() => {
        let rows: Row[];
        if (granularity) {
            rows = [...buckets]
                .sort((a, b) => a.bucketIndex - b.bucketIndex)
                .map((bucket, index) => {
                    const periodStart = bucket.periodStart ?? '';
                    const date = formatPeriodTooltipDate(periodStart, granularity, locale);
                    return {
                        key: periodStart,
                        label: formatPeriodTick(periodStart, granularity, locale, index === 0 || periodStart.slice(5, 7) === '01'),
                        tooltipLabel: granularity === 'week' ? tPage('weekOf', { date }) : date,
                        Call: bucket.call,
                        Email: bucket.email,
                        Meeting: bucket.meeting,
                        Note: bucket.note,
                        Other: bucket.other,
                    };
                });
        } else {
            const labels = buildTimeBuckets(range ?? '90d', now, locale, timeZone);
            const byIndex = new Map(buckets.map((bucket) => [bucket.bucketIndex, bucket]));
            rows = labels.map((slot, index) => {
                const bucket = byIndex.get(index);
                const key = new Date(slot.start).toISOString().slice(0, 10);
                return {
                    key,
                    label: range === '12m' ? formatPeriodTick(key, 'month', locale, index === 0 || key.slice(5, 7) === '01') : slot.label,
                    tooltipLabel: slot.label,
                    Call: bucket?.call ?? 0,
                    Email: bucket?.email ?? 0,
                    Meeting: bucket?.meeting ?? 0,
                    Note: bucket?.note ?? 0,
                    Other: bucket?.other ?? 0,
                };
            });
        }
        return {
            data: rows,
            activeTypes: ACTIVITY_TYPES.filter(
                (type) => rows.reduce((sum, row) => sum + row[type], 0) > 0,
            ),
            tooltipLabels: new Map(rows.map((row) => [row.key, row.tooltipLabel])),
        };
    }, [buckets, granularity, range, now, locale, tPage, timeZone]);

    const total = useMemo(
        () => data.reduce((sum, row) => sum + ACTIVITY_TYPES.reduce((s, type) => s + row[type], 0), 0),
        [data],
    );

    const chartConfig = useMemo<ChartConfig>(() => {
        const cfg: ChartConfig = {};
        for (const type of ACTIVITY_TYPES) {
            cfg[type] = { label: t(`type${type}`), color: ACTIVITY_COLORS[type] };
        }
        return cfg;
    }, [t]);
    const monthly = granularity === 'month' || (!granularity && range === '12m');

    if (total === 0) {
        return <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <BarChart data={data} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                    dataKey="key"
                    padding={{ left: 12, right: 32 }}
                    tickFormatter={(value: string) => data.find((row) => row.key === value)?.label ?? value}
                    ticks={monthly ? monthAxisTicks(data.map((row) => row.key)) : undefined}
                    tickLine={false}
                    axisLine={false}
                    tickMargin={10}
                    minTickGap={16}
                    interval={monthly ? 0 : 'preserveStartEnd'}
                    tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                />
                <YAxis tickLine={false} axisLine={false} {...autoYAxisWidth()} allowDecimals={false} tick={{ fontSize: 11, fill: 'var(--chart-axis)' }} />
                <ChartTooltip
                    cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                    content={
                        <ChartTooltipContent
                            labelFormatter={(value) => tooltipLabels.get(String(value)) ?? String(value)}
                        />
                    }
                />
                {activeTypes.map((type, i) => (
                    <Bar
                        key={type}
                        dataKey={type}
                        stackId="activity"
                        fill={ACTIVITY_COLORS[type]}
                        radius={i === activeTypes.length - 1 ? [4, 4, 0, 0] : 0}
                    />
                ))}
                <ChartLegend content={<ChartLegendContent />} />
            </BarChart>
        </ChartContainer>
    );
}
