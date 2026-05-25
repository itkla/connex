'use client';

import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, XAxis, YAxis, Tooltip as RechartsTooltip } from 'recharts';

import { ChartContainer, ChartLegend, ChartLegendContent, type ChartConfig } from '@/components/ui/chart';
import { type Deal, type Stage } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';

const BUCKETS = [
    { key: 'fresh', label: '0-7d', max: 7, color: '#22c55e' },
    { key: 'active', label: '8-30d', max: 30, color: '#84cc16' },
    { key: 'aging', label: '31-60d', max: 60, color: '#f59e0b' },
    { key: 'stalled', label: '60+d', max: Infinity, color: '#ef4444' },
] as const;

type BucketKey = (typeof BUCKETS)[number]['key'];

const chartConfig: ChartConfig = Object.fromEntries(
    BUCKETS.map((b) => [b.key, { label: b.label, color: b.color }]),
);

function isClosed(deal: Deal): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= Date.now();
}

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
    const data = useMemo<Row[]>(() => {
        const byStage = new Map<string, Row>();
        for (const deal of deals) {
            if (isClosed(deal) || deal.stage == null) continue;
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
            <div className="flex h-64 items-center justify-center text-sm text-neutral-500">
                No open deals to age
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
                    tick={{ fontSize: 11, fill: '#737373' }}
                    allowDecimals={false}
                />
                <YAxis
                    type="category"
                    dataKey="stage"
                    tickLine={false}
                    axisLine={false}
                    width={96}
                    tick={{ fontSize: 11, fill: '#737373' }}
                />
                <RechartsTooltip
                    cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                    content={<AgingTooltip />}
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
}

function AgingTooltip({ active, payload }: AgingTooltipProps) {
    if (!active || !payload?.length) return null;
    const row = payload[0].payload;
    return (
        <div className="rounded-md bg-white p-2 text-xs ring-1 ring-black/5 shadow-md">
            <div className="font-medium text-neutral-700 mb-1.5">{row.stage}</div>
            <div className="space-y-0.5">
                {payload
                    .filter((p) => p.value > 0)
                    .map((p) => (
                        <div key={p.dataKey} className="flex items-center gap-1.5 text-neutral-600">
                            <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: p.fill }} />
                            {BUCKETS.find((b) => b.key === p.dataKey)?.label} · {p.value}{' '}
                            {p.value === 1 ? 'deal' : 'deals'}
                        </div>
                    ))}
            </div>
        </div>
    );
}