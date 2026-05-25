'use client';

import { useMemo, useEffect, useState } from 'react';
import { Pie, PieChart, Tooltip as RechartsTooltip } from 'recharts';

import {
    ChartContainer,
    type ChartConfig,
} from '@/components/ui/chart';
import { Pipeline, Stage, type Deal } from '@/app/lib/types';
import { getPipelines, getStagesByPipelineId } from '@/app/lib/api';
import { formatCompactCurrency, parseMysqlDateTime } from '@/app/lib/utils';

const PIPELINE_PALETTE = [
    '#0ea5e9', // sky-500
    '#a855f7', // purple-500
    'var(--color-chart-2)',
    'var(--color-chart-5)',
];

const STAGE_WON = '#22c55e';  // green
const STAGE_LOST = '#ef4444'; // red
const STAGE_OPEN = '#fcd34d'; // amber

function colorForStage(name: string, index: number, total: number) {
    const n = name.toLowerCase();
    if (/(?:\bwon\b|renew|complet)/.test(n)) return STAGE_WON;
    if (/(?:lost|churn)/.test(n)) return STAGE_LOST;
    const lighten = total <= 1 ? 0 : (1 - index / (total - 1)) * 40;
    return `color-mix(in oklch, ${STAGE_OPEN} ${100 - lighten}%, white)`;
}

function fadeColor(color: string, amount = 55) {
    return `color-mix(in oklch, ${color} ${100 - amount}%, white)`;
}

function isClosed(deal: Deal): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= Date.now();
}

type DealStatus = 'open' | 'closed';

// TODO: move this to @/app/lib/types.ts so that it can be used in other components
type PipelineDatum = { name: string; status: DealStatus; value: number; total: number; currency: string; fill: string };
type StageDatum = { name: string; pipelineName: string; status: DealStatus; value: number; total: number; currency: string; fill: string };

export default function StageRatio({ deals }: { deals: Deal[] }) {
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});

    useEffect(() => {
        let cancelled = false;
        getPipelines()
            .then(async (pls) => {
                if (cancelled) return;
                setPipelines(pls);
                const entries = await Promise.all(
                    pls.map((pipeline) =>
                        getStagesByPipelineId(pipeline.id).then(
                            (stages) => [pipeline.id, stages] as const,
                        ),
                    ),
                );
                if (cancelled) return;
                setStagesByPipeline(Object.fromEntries(entries));
            })
            .catch((error) => {
                console.error(error);
                if (!cancelled) setStagesByPipeline({});
            });
        return () => {
            cancelled = true;
        };
    }, []);

    const pipelineColorById = useMemo(() => {
        // TODO: inverse the palette so that the closed pipelines are darker and the open ones are lighter
        const map = new Map<number, string>();
        pipelines.forEach((p, i) =>
            map.set(p.id, PIPELINE_PALETTE[i % PIPELINE_PALETTE.length]),
        );
        return map;
    }, [pipelines]);

    const pipelineData = useMemo<PipelineDatum[]>(
        () =>
            pipelines.flatMap((pipeline) => {
                const base = pipelineColorById.get(pipeline.id) ?? PIPELINE_PALETTE[0];
                const pipelineDeals = deals.filter((d) => d.pipeline === pipeline.id);
                const currency = pipelineDeals[0]?.currency || 'USD';
                const bucket = (open: boolean) => {
                    const matches = pipelineDeals.filter((d) => isClosed(d) !== open);
                    return {
                        value: matches.length,
                        total: matches.reduce((sum, d) => sum + ((open ? d.value : d.actualValue) ?? 0), 0),
                    };
                };
                const openBucket = bucket(true);
                const closedBucket = bucket(false);
                return [
                    { name: pipeline.name, status: 'open' as const, ...openBucket, currency, fill: base },
                    { name: pipeline.name, status: 'closed' as const, ...closedBucket, currency, fill: fadeColor(base) },
                ];
            }),
        [deals, pipelines, pipelineColorById],
    );

    const stageData = useMemo<StageDatum[]>(
        () =>
            pipelines.flatMap((pipeline) => {
                const stages = stagesByPipeline[pipeline.id] ?? [];
                return (['open', 'closed'] as const).flatMap((status) =>
                    stages.map((stage, i) => {
                        const baseColor = colorForStage(stage.name, i, stages.length);
                        const matches = deals.filter(
                            (d) => d.stage === stage.id && (status === 'closed' ? isClosed(d) : !isClosed(d)),
                        );
                        return {
                            name: stage.name,
                            pipelineName: pipeline.name,
                            status,
                            value: matches.length,
                            total: matches.reduce((sum, d) => sum + ((status === 'closed' ? d.actualValue : d.value) ?? 0), 0),
                            currency: matches[0]?.currency || 'USD',
                            fill: status === 'closed' ? fadeColor(baseColor, 40) : baseColor,
                        };
                    }),
                );
            }),
        [deals, pipelines, stagesByPipeline],
    );

    const chartConfig = useMemo<ChartConfig>(() => {
        const cfg: ChartConfig = {};
        pipelines.forEach((p) => {
            cfg[`pipeline-${p.id}`] = {
                label: p.name,
                color: pipelineColorById.get(p.id),
            };
        });
        return cfg;
    }, [pipelines, pipelineColorById]);

    return (
        <ChartContainer config={chartConfig} className="aspect-square h-64 w-full">
            <PieChart>
                <RechartsTooltip
                    cursor={{ fill: 'var(--color-brand)', fillOpacity: 0.05 }}
                    content={<StageRatioTooltip />}
                />
                <Pie
                    data={pipelineData}
                    dataKey="value"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    outerRadius="50%"
                    stroke="white"
                    strokeWidth={1}
                />
                <Pie
                    data={stageData}
                    dataKey="value"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    innerRadius="60%"
                    outerRadius="80%"
                    stroke="white"
                    strokeWidth={1}
                />
            </PieChart>
        </ChartContainer>
    );
}

interface StageRatioTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: PipelineDatum | StageDatum }>;
}

function StageRatioTooltip({ active, payload }: StageRatioTooltipProps) {
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    const isStage = 'pipelineName' in d;
    const statusLabel = d.status === 'closed' ? 'Closed' : 'Open';
    const title = isStage
        ? `${d.pipelineName} · ${d.name} · ${statusLabel}`
        : `${d.name} · ${statusLabel}`;
    return (
        <div className="rounded-md bg-white p-2 text-xs ring-1 ring-black/5 shadow-md">
            <div className="font-medium text-neutral-700 mb-1.5">{title}</div>
            <div className="flex items-center gap-1.5 text-neutral-600">
                <span
                    className="inline-block size-2 rounded-sm"
                    style={{ backgroundColor: d.fill }}
                />
                {d.value} {d.value === 1 ? 'deal' : 'deals'} · {formatCompactCurrency(d.total, d.currency)}
            </div>
        </div>
    );
}