'use client';

import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';
import { useTranslations } from 'next-intl';

import { ChartContainer, ChartLegend, ChartLegendContent, type ChartConfig } from '@/components/ui/chart';
import { type Deal, type Stage } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { isDealClosed } from './dealOutcome';

const BUCKETS = [
    { key: 'fresh', labelKey: 'bucketFresh', max: 7, color: '#22c55e' },
    { key: 'active', labelKey: 'bucketActive', max: 30, color: '#84cc16' },
    { key: 'aging', labelKey: 'bucketAging', max: 60, color: '#f59e0b' },
    { key: 'stalled', labelKey: 'bucketStalled', max: Infinity, color: '#ef4444' },
] as const;

type BucketKey = (typeof BUCKETS)[number]['key'];
type BucketLabelKey = (typeof BUCKETS)[number]['labelKey'];

function daysSince(value?: string): number {
    const t = parseMysqlDateTime(value);
    if (!Number.isFinite(t)) return 0;
    return Math.max(0, (Date.now() - t) / (1000 * 60 * 60 * 24));
}

function bucketFor(days: number): BucketKey {
    for (const b of BUCKETS) {
        if (days <= b.max) return b.key;
    }
    return BUCKETS[BUCKETS.length - 1].key;
}

type Row = { stage: string } & Record<BucketKey, number>;

export default function DealsAging({ deals, stageById }: { deals: Deal[]; stageById: Map<number, Stage> }) {
    const t = useTranslations('DealsAging');
    const chartConfig: ChartConfig = useMemo(
        () => Object.fromEntries(BUCKETS.map((b) => [b.key, { label: t(b.labelKey), color: b.color }])),
        [t],
    );
    const data = useMemo<Row[]>(() => {
        const byStage = new Map<string, Row>();
        for (const deal of deals) {
            if (isDealClosed(deal) || deal.stage == null) continue;
            const stageName = stageById.get(deal.stage)?.name ?? `Stage ${deal.stage}`;
            const bucket = bucketFor(daysSince(deal.updatedAt));
            const row =
                byStage.get(stageName) ??
                ({ stage: stageName, fresh: 0, active: 0, aging: 0, stalled: 0 } as Row);
            row[bucket] = row[bucket] + 1;
            byStage.set(stageName, row);
        }
        return Array.from(byStage.values()).sort((a, b) => {
            const totalA = a.fresh + a.active + a.aging + a.stalled;
            const totalB = b.fresh + b.active + b.aging + b.stalled;
            return totalB - totalA;
        });
    }, [deals, stageById]);

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
                {payload
                    .filter((p) => p.value > 0)
                    .map((p) => (
                        <div key={p.dataKey} className="flex items-center gap-1.5 text-muted-foreground">
                            <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: p.fill }} />
                            {bucketLabel ? bucketLabel(p.dataKey as BucketKey) : p.dataKey} · {dealsLabel ? dealsLabel(p.value) : p.value}
                        </div>
                    ))}
            </div>
        </div>
    );
}