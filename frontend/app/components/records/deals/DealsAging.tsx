'use client';

import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';
import { useTranslations } from 'next-intl';

import { ChartContainer, ChartLegend, ChartLegendContent, type ChartConfig } from '@/components/ui/chart';
import { type DealAging, type Stage } from '@/app/lib/types';

const BUCKETS = [
    { key: 'fresh', labelKey: 'bucketFresh', color: 'var(--warmth-hot)' },
    { key: 'active', labelKey: 'bucketActive', color: 'var(--warmth-warm)' },
    { key: 'aging', labelKey: 'bucketAging', color: 'var(--chart-open)' },
    { key: 'stalled', labelKey: 'bucketStalled', color: 'var(--chart-lost)' },
] as const;

type BucketKey = (typeof BUCKETS)[number]['key'];

type Row = { stage: string } & Record<BucketKey, number>;

/**
 * Per-stage open-deal age distribution from the server-computed {@link DealAging} buckets.
 * Stage names are joined from {@code stageById}; aging math happens server-side.
 */
export default function DealsAging({ aging, stageById }: { aging: DealAging[]; stageById: Map<number, Stage> }) {
    const t = useTranslations('DealsAging');
    const chartConfig: ChartConfig = useMemo(
        () => Object.fromEntries(BUCKETS.map((b) => [b.key, { label: t(b.labelKey), color: b.color }])),
        [t],
    );
    const data = useMemo<Row[]>(() => {
        const rows: Row[] = [];
        for (const entry of aging) {
            if (entry.stageId == null) continue;
            const row = {
                stage: stageById.get(entry.stageId)?.name ?? `Stage ${entry.stageId}`,
                fresh: entry.fresh,
                active: entry.active,
                aging: entry.aging,
                stalled: entry.stalled,
            };
            if (row.fresh + row.active + row.aging + row.stalled > 0) rows.push(row);
        }
        return rows.sort((a, b) => {
            const totalA = a.fresh + a.active + a.aging + a.stalled;
            const totalB = b.fresh + b.active + b.aging + b.stalled;
            return totalB - totalA;
        });
    }, [aging, stageById]);

    if (data.length === 0) {
        return (
            <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">
                {t('noOpenDealsToAge')}
            </div>
        );
    }

    return (
        <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
            <BarChart data={data} layout="vertical" margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                <CartesianGrid horizontal={false} strokeDasharray="3 3" />
                <XAxis
                    type="number"
                    tickLine={false}
                    axisLine={false}
                    tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                    allowDecimals={false}
                />
                <YAxis
                    type="category"
                    dataKey="stage"
                    tickLine={false}
                    axisLine={false}
                    width={96}
                    tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
                />
                <RechartsTooltip
                    cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                    content={
                        <AgingTooltip
                            bucketLabel={(key) => t(BUCKETS.find((b) => b.key === key)?.labelKey ?? 'bucketFresh')}
                            dealsLabel={(value) => t('deals', { value })}
                        />
                    }
                />
                {BUCKETS.map((b) => (
                    <Bar key={b.key} dataKey={b.key} stackId="age" fill={b.color} />
                ))}
                <ChartLegend content={<ChartLegendContent />} />
            </BarChart>
        </ChartContainer>
    );
}

interface AgingTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: Row; value: number; dataKey: string; fill: string }>;
    bucketLabel?: (key: BucketKey) => string;
    dealsLabel?: (value: number) => string;
}

function AgingTooltip({ active, payload, bucketLabel, dealsLabel }: AgingTooltipProps) {
    if (!active || !payload?.length) return null;
    const row = payload[0].payload;
    return (
        <div className="rounded-md bg-popover text-popover-foreground p-2 text-xs border border-border shadow-md">
            <div className="font-medium text-popover-foreground mb-1.5">{row.stage}</div>
            <div className="space-y-0.5">
                {payload.map((p) =>
                    p.value > 0 ? (
                        <div key={p.dataKey} className="flex items-center gap-1.5 text-muted-foreground">
                            <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: p.fill }} />
                            {bucketLabel ? bucketLabel(p.dataKey as BucketKey) : p.dataKey} · {dealsLabel ? dealsLabel(p.value) : p.value}
                        </div>
                    ) : null,
                )}
            </div>
        </div>
    );
}
