'use client';

import { useMemo, useEffect, useState } from 'react';
import { Pie, PieChart, Tooltip as RechartsTooltip } from 'recharts';
import { useLocale, useTranslations } from 'next-intl';

import {
    ChartContainer,
    type ChartConfig,
} from '@/components/ui/chart';
import { Pipeline, Stage, type DealStageDistribution } from '@/app/lib/types';
import { getPipelines, getStagesByPipelineId } from '@/app/lib/api';
import { formatCompactCurrency } from '@/app/lib/utils';
import { classifyStage, type StageClass } from './dealOutcome';

const PIPELINE_PALETTE = [
    '#0ea5e9', // sky-500
    '#a855f7', // purple-500
    'var(--color-chart-2)',
    'var(--color-chart-5)',
];

const STAGE_WON = 'var(--chart-won)';  // green
const STAGE_LOST = 'var(--chart-lost)'; // red
const STAGE_OPEN = 'var(--chart-open)'; // amber

function colorForStage(klass: StageClass, index: number, total: number) {
    if (klass === 'won') return STAGE_WON;
    if (klass === 'lost') return STAGE_LOST;
    const lighten = total <= 1 ? 0 : (1 - index / (total - 1)) * 40;
    return `color-mix(in oklch, ${STAGE_OPEN} ${100 - lighten}%, var(--card))`;
}

function fadeColor(color: string, amount = 55) {
    return `color-mix(in oklch, ${color} ${100 - amount}%, var(--card))`;
}

type DealStatus = 'open' | 'closed';

// TODO: move this to @/app/lib/types.ts so that it can be used in other components
type PipelineDatum = { name: string; status: DealStatus; value: number; total: number; currency: string; fill: string };
type StageDatum = { name: string; pipelineName: string; status: DealStatus; value: number; total: number; currency: string; fill: string };

export default function StageRatio({ distribution, currency }: { distribution: DealStageDistribution[]; currency: string }) {
    const t = useTranslations('DealsStageRatio');
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

    const distByStage = useMemo(
        () => new Map(distribution.map((d) => [d.stageId, d])),
        [distribution],
    );

    const pipelineData = useMemo<PipelineDatum[]>(
        () =>
            pipelines.flatMap((pipeline) => {
                const base = pipelineColorById.get(pipeline.id) ?? PIPELINE_PALETTE[0];
                const rows = distribution.filter((d) => d.pipelineId === pipeline.id);
                const openCount = rows.reduce((sum, d) => sum + d.openCount, 0);
                const openValue = rows.reduce((sum, d) => sum + d.openValue, 0);
                const closedCount = rows.reduce((sum, d) => sum + d.closedCount, 0);
                const closedValue = rows.reduce((sum, d) => sum + d.closedValue, 0);
                return [
                    { name: pipeline.name, status: 'open' as const, value: openCount, total: openValue, currency, fill: base },
                    { name: pipeline.name, status: 'closed' as const, value: closedCount, total: closedValue, currency, fill: fadeColor(base) },
                ];
            }),
        [distribution, pipelines, pipelineColorById, currency],
    );

    const stageData = useMemo<StageDatum[]>(
        () =>
            pipelines.flatMap((pipeline) => {
                const stages = stagesByPipeline[pipeline.id] ?? [];
                return (['open', 'closed'] as const).flatMap((status) =>
                    stages.map((stage, i) => {
                        const klass = classifyStage(stage);
                        const baseColor = colorForStage(klass, i, stages.length);
                        const row = distByStage.get(stage.id);
                        return {
                            name: stage.name,
                            pipelineName: pipeline.name,
                            status,
                            value: status === 'closed' ? (row?.closedCount ?? 0) : (row?.openCount ?? 0),
                            total: status === 'closed' ? (row?.closedValue ?? 0) : (row?.openValue ?? 0),
                            currency,
                            fill: status === 'closed' ? fadeColor(baseColor, 40) : baseColor,
                        };
                    }),
                );
            }),
        [distByStage, pipelines, stagesByPipeline, currency],
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
                    content={
                        <StageRatioTooltip
                            openLabel={t('open')}
                            closedLabel={t('closed')}
                            dealsLabel={(value) => t('deals', { value })}
                        />
                    }
                />
                <Pie
                    data={pipelineData}
                    dataKey="value"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    outerRadius="50%"
                    stroke="var(--chart-stroke)"
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
                    stroke="var(--chart-stroke)"
                    strokeWidth={1}
                />
            </PieChart>
        </ChartContainer>
    );
}

interface StageRatioTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: PipelineDatum | StageDatum }>;
    openLabel?: string;
    closedLabel?: string;
    dealsLabel?: (value: number) => string;
}

function StageRatioTooltip({ active, payload, openLabel, closedLabel, dealsLabel }: StageRatioTooltipProps) {
    const locale = useLocale();
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    const isStage = 'pipelineName' in d;
    const statusLabel = d.status === 'closed' ? (closedLabel ?? 'Closed') : (openLabel ?? 'Open');
    const title = isStage
        ? `${d.pipelineName} · ${d.name} · ${statusLabel}`
        : `${d.name} · ${statusLabel}`;
    return (
        <div className="rounded-md bg-popover text-popover-foreground p-2 text-xs border border-border shadow-md">
            <div className="font-medium text-popover-foreground mb-1.5">{title}</div>
            <div className="flex items-center gap-1.5 text-muted-foreground">
                <span
                    className="inline-block size-2 rounded-sm"
                    style={{ backgroundColor: d.fill }}
                />
                {dealsLabel ? dealsLabel(d.value) : `${d.value} ${d.value === 1 ? 'deal' : 'deals'}`} · {formatCompactCurrency(d.total, d.currency, locale)}
            </div>
        </div>
    );
}