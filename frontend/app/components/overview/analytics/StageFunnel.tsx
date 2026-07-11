'use client';

import { useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronDownIcon } from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { type DealStageDistribution, type Pipeline, type Stage } from '@/app/lib/types';
import { formatCompactCurrency } from '@/app/lib/utils';
import { classifyStage } from '@/app/components/records/deals/dealOutcome';

type Row = {
    id: number;
    name: string;
    count: number;
    value: number;
    fill: string;
};

// class is a protected keyword in TypeScript, so ima use klass instead
function stageFill(index: number, total: number, klass: string): string {
    if (klass === 'won') return 'var(--chart-won)';
    if (klass === 'lost') return 'var(--chart-lost)';
    const t = total <= 1 ? 0 : index / (total - 1);
    const lighten = 55 - t * 45;
    return `color-mix(in oklch, var(--color-brand) ${100 - lighten}%, var(--card))`;
}

/**
 * Open-deal stage funnel per pipeline from the server-computed {@link DealStageDistribution}
 * rollup. Uses each stage's open count/value; stage and pipeline names are joined from the loaded
 * {@code stages}/{@code pipelines}.
 */
export default function StageFunnel({
    distribution,
    pipelines,
    stages,
    currency,
}: {
    distribution: DealStageDistribution[];
    pipelines: Pipeline[];
    stages: Stage[];
    currency: string;
}) {
    const t = useTranslations('AnalyticsStageFunnel');
    const locale = useLocale();
    const [selectedId, setSelectedId] = useState<number | null>(null);

    const distByStage = useMemo(() => {
        const map = new Map<number, DealStageDistribution>();
        for (const row of distribution) {
            if (row.stageId != null) map.set(row.stageId, row);
        }
        return map;
    }, [distribution]);

    const available = useMemo(() => {
        const counts = new Map<number, number>();
        for (const row of distribution) {
            if (row.pipelineId == null || row.openCount <= 0) continue;
            counts.set(row.pipelineId, (counts.get(row.pipelineId) ?? 0) + row.openCount);
        }
        return pipelines
            .filter((p) => (counts.get(p.id) ?? 0) > 0)
            .map((p) => ({ id: p.id, name: p.name, openCount: counts.get(p.id) ?? 0 }))
            .sort((a, b) => b.openCount - a.openCount);
    }, [distribution, pipelines]);

    const activeId =
        selectedId != null && available.some((p) => p.id === selectedId) ? selectedId : available[0]?.id ?? null;

    const rows = useMemo<Row[]>(() => {
        if (activeId == null) return [];
        const pipelineStages = stages
            .filter((s) => s.pipeline === activeId)
            .sort((a, b) => a.position - b.position);
        return pipelineStages
            .map((stage, i) => {
                const entry = distByStage.get(stage.id);
                return {
                    id: stage.id,
                    name: stage.name,
                    count: entry?.openCount ?? 0,
                    value: entry?.openValue ?? 0,
                    fill: stageFill(i, pipelineStages.length, classifyStage(stage)),
                };
            })
            .filter((r) => r.count > 0);
    }, [activeId, stages, distByStage]);

    if (available.length === 0) {
        return <div className="flex h-56 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const activeName = available.find((p) => p.id === activeId)?.name ?? '';
    const maxValue = Math.max(...rows.map((r) => r.value), 1);

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5">
                {available.length > 1 ? (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                aria-label={t('selectPipeline')}
                                className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-sm font-medium text-foreground ring-1 ring-border transition hover:bg-muted/80"
                            >
                                {activeName}
                                <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start">
                            {available.map((p) => (
                                <DropdownMenuItem key={p.id} onSelect={() => setSelectedId(p.id)}>
                                    <span className={p.id === activeId ? 'font-semibold' : ''}>{p.name}</span>
                                    <span className="ml-auto text-xs text-muted-foreground">
                                        {t('deals', { count: p.openCount })}
                                    </span>
                                </DropdownMenuItem>
                            ))}
                        </DropdownMenuContent>
                    </DropdownMenu>
                ) : (
                    <p className="text-sm text-muted-foreground">{t('subtitle', { pipeline: activeName })}</p>
                )}
            </div>
            {rows.length === 0 ? (
                <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>
            ) : (
                <ul className="flex flex-col gap-3">
                    {rows.map((row) => {
                        const width = Math.max(6, (row.value / maxValue) * 100);
                        return (
                            <li key={row.id} className="group">
                                <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                                    <span className="min-w-0 truncate font-medium text-foreground">{row.name}</span>
                                    <span className="shrink-0 tabular-nums text-muted-foreground">
                                        {t('deals', { count: row.count })}
                                    </span>
                                </div>
                                <div className="flex items-center gap-3">
                                    <div className="h-7 flex-1 overflow-hidden rounded-md bg-muted">
                                        <div
                                            className="h-full rounded-md transition-[width] duration-500 ease-out group-hover:brightness-95 motion-reduce:transition-none"
                                            style={{ width: `${width}%`, backgroundColor: row.fill }}
                                        />
                                    </div>
                                    <span className="w-16 shrink-0 text-right text-sm tabular-nums text-foreground">
                                        {formatCompactCurrency(row.value, currency, locale)}
                                    </span>
                                </div>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}