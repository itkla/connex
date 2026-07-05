'use client';

import { useMemo, useState } from 'react';
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
import { buildTimeBuckets, type RangeKey } from '@/app/components/overview/analytics/metrics';

export default function IntroActivity({
    suggestions,
    lineage,
    range,
}: {
    suggestions: IntroSuggestion[];
    lineage: IntroductionRecord[];
    range: RangeKey;
}) {
    const t = useTranslations('AnalyticsIntros');
    const locale = useLocale();
    const [now] = useState(() => Date.now());

    const { data, made } = useMemo(() => {
        const buckets = buildTimeBuckets(range, now, locale);
        const rows = buckets.map((bucket) => ({ label: bucket.label, made: 0 }));
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
    }, [lineage, range, now, locale]);

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
                    <BarChart data={data} margin={{ top: 8, right: 4, left: -16, bottom: 0 }}>
                        <CartesianGrid vertical={false} strokeDasharray="3 3" />
                        <XAxis
                            dataKey="label"
                            tickLine={false}
                            axisLine={false}
                            tickMargin={10}
                            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                        />
                        <YAxis
                            tickLine={false}
                            axisLine={false}
                            tickMargin={4}
                            width={28}
                            allowDecimals={false}
                            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                        />
                        <ChartTooltip
                            cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                            content={<ChartTooltipContent />}
                        />
                        <Bar dataKey="made" fill="var(--color-brand)" radius={[4, 4, 0, 0]} />
                    </BarChart>
                </ChartContainer>
            )}
        </div>
    );
}
