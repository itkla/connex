'use client';

import { useMemo, useState } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

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
    type ActivityType,
    type RangeKey,
} from './metrics';

type Row = { label: string } & Record<ActivityType, number>;

/**
 * Stacked activity-volume chart. Consumes server-aggregated {@link ActivityVolumeBucket}s
 * (oldest→newest by {@code bucketIndex}) and renders one bar per time bucket. The {@code range}
 * only drives the human-readable bucket labels via {@link buildTimeBuckets}.
 */
export default function ActivityVolume({
    buckets,
    range,
}: {
    buckets: ActivityVolumeBucket[];
    range: RangeKey;
}) {
    const t = useTranslations('AnalyticsActivity');
    const locale = useLocale();
    const [now] = useState(() => Date.now());

    const { data, activeTypes } = useMemo(() => {
        const labels = buildTimeBuckets(range, now, locale);
        const byIndex = new Map(buckets.map((bucket) => [bucket.bucketIndex, bucket]));
        const rows: Row[] = labels.map((slot, index) => {
            const bucket = byIndex.get(index);
            return {
                label: slot.label,
                Call: bucket?.call ?? 0,
                Email: bucket?.email ?? 0,
                Meeting: bucket?.meeting ?? 0,
                Note: bucket?.note ?? 0,
                Other: bucket?.other ?? 0,
            };
        });
        return {
            data: rows,
            activeTypes: ACTIVITY_TYPES.filter(
                (type) => rows.reduce((sum, row) => sum + row[type], 0) > 0,
            ),
        };
    }, [buckets, range, now, locale]);

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

    if (total === 0) {
        return <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <BarChart data={data} margin={{ top: 8, right: 4, left: -16, bottom: 0 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={10} tick={{ fontSize: 11, fill: 'var(--chart-axis)' }} />
                <YAxis tickLine={false} axisLine={false} tickMargin={4} width={36} allowDecimals={false} tick={{ fontSize: 11, fill: 'var(--chart-axis)' }} />
                <ChartTooltip cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }} content={<ChartTooltipContent />} />
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
