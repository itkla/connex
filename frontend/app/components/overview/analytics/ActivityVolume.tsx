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
import { type Activity } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import {
    ACTIVITY_COLORS,
    ACTIVITY_TYPES,
    buildTimeBuckets,
    normalizeActivityType,
    type ActivityType,
    type RangeKey,
} from './metrics';

type Row = { label: string } & Record<ActivityType, number>;

export default function ActivityVolume({ activities, range }: { activities: Activity[]; range: RangeKey }) {
    const t = useTranslations('AnalyticsActivity');
    const locale = useLocale();
    const [now] = useState(() => Date.now());

    const { data, activeTypes } = useMemo(() => {
        const buckets = buildTimeBuckets(range, now, locale);
        const rows: Row[] = buckets.map((b) => ({
            label: b.label,
            Call: 0,
            Email: 0,
            Meeting: 0,
            Note: 0,
            Other: 0,
        }));
        const present = new Set<ActivityType>();
        for (const activity of activities) {
            const ts = parseMysqlDateTime(activity.timestamp);
            if (!Number.isFinite(ts)) continue;
            const idx = buckets.findIndex((b) => ts >= b.start && ts < b.end);
            if (idx < 0) continue;
            const type = normalizeActivityType(activity.type);
            rows[idx][type] += 1;
            present.add(type);
        }
        return {
            data: rows,
            activeTypes: ACTIVITY_TYPES.filter((type) => present.has(type)),
        };
    }, [activities, range, now, locale]);

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
        return <div className="flex h-64 items-center justify-center text-sm text-neutral-500">{t('empty')}</div>;
    }

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <BarChart data={data} margin={{ top: 8, right: 4, left: -16, bottom: 0 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={10} tick={{ fontSize: 11, fill: '#737373' }} />
                <YAxis tickLine={false} axisLine={false} tickMargin={4} width={36} allowDecimals={false} tick={{ fontSize: 11, fill: '#737373' }} />
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