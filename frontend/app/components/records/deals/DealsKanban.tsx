'use client';

import { useCallback, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import type { Announcements, ScreenReaderInstructions, UniqueIdentifier } from '@dnd-kit/core';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import DealCard from '@/app/components/records/deals/DealCard';
import { classifyStage } from './dealOutcome';
import { moveDeal } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import type { Company, Deal, Pipeline, Stage } from '@/app/lib/types';

interface DealsKanbanProps {
    deals: Deal[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    companyById: Map<number, Company>;
    pipelineById: Map<number, Pipeline>;
    stageById: Map<number, Stage>;
    onQuickEdit: (deal: Deal) => void;
    onDelete: (deal: Deal) => void;
    onMoved: () => void;
    reduce: boolean;
}

function stageAccent(stage: Stage): string {
    const cls = classifyStage(stage);
    return cls === 'won' ? 'var(--chart-won)' : cls === 'lost' ? 'var(--chart-lost)' : 'var(--chart-open)';
}

export default function DealsKanban({
    deals,
    pipelines,
    stagesByPipeline,
    companyById,
    pipelineById,
    stageById,
    onQuickEdit,
    onDelete,
    onMoved,
    reduce,
}: DealsKanbanProps) {
    const t = useTranslations('DealsKanban');

    const pipelineOptions = useMemo(
        () => pipelines.filter((p) => (stagesByPipeline[p.id]?.length ?? 0) > 0),
        [pipelines, stagesByPipeline],
    );

    const defaultPipelineId = useMemo(() => {
        const counts = new Map<number, number>();
        for (const d of deals) if (d.pipeline != null) counts.set(d.pipeline, (counts.get(d.pipeline) ?? 0) + 1);
        let best: number | null = null;
        let bestCount = -1;
        for (const [pid, n] of counts) {
            if (n > bestCount && stagesByPipeline[pid]?.length) {
                best = pid;
                bestCount = n;
            }
        }
        return best ?? pipelineOptions[0]?.id ?? null;
    }, [deals, stagesByPipeline, pipelineOptions]);

    const [selected, setSelected] = useState<number | null>(null);
    const selectedPipelineId =
        selected != null && (stagesByPipeline[selected]?.length ?? 0) > 0 ? selected : defaultPipelineId;

    const columns: KanbanColumnDef[] = useMemo(() => {
        const stages = selectedPipelineId != null ? stagesByPipeline[selectedPipelineId] ?? [] : [];
        return [...stages]
            .sort((a, b) => a.position - b.position)
            .map((s) => ({ id: String(s.id), label: s.name, accent: stageAccent(s) }));
    }, [selectedPipelineId, stagesByPipeline]);

    const boardDeals = useMemo(
        () => deals.filter((d) => d.pipeline === selectedPipelineId),
        [deals, selectedPipelineId],
    );

    const dealsById = useMemo(() => new Map(boardDeals.map((d) => [d.id, d])), [boardDeals]);

    const renderCard = useCallback(
        (deal: Deal) => (
            <DealCard
                deal={deal}
                company={deal.company != null ? companyById.get(deal.company) : undefined}
                pipeline={deal.pipeline != null ? pipelineById.get(deal.pipeline) : undefined}
                stage={deal.stage != null ? stageById.get(deal.stage) : undefined}
                onQuickEdit={() => onQuickEdit(deal)}
                onDelete={() => onDelete(deal)}
            />
        ),
        [companyById, pipelineById, stageById, onQuickEdit, onDelete],
    );

    const onMove = useCallback(
        async (dealId: number, colId: string, index: number) => {
            try {
                await moveDeal(dealId, Number(colId), index);
                onMoved();
            } catch (err) {
                toastError(err instanceof Error ? err.message : t('moveFailed'));
                throw err;
            }
        },
        [onMoved, t],
    );

    const dealName = useCallback((id: UniqueIdentifier) => dealsById.get(Number(id))?.name ?? '', [dealsById]);
    const columnName = useCallback(
        (id: UniqueIdentifier) => {
            const s = String(id);
            const stageId = s.startsWith('col:') ? Number(s.slice(4)) : dealsById.get(Number(id))?.stage ?? null;
            return stageId != null ? stageById.get(stageId)?.name ?? '' : '';
        },
        [dealsById, stageById],
    );

    const announcements: Announcements = useMemo(
        () => ({
            onDragStart: ({ active }) => t('a11yLifted', { name: dealName(active.id) }),
            onDragOver: ({ active, over }) =>
                over ? t('a11yOver', { name: dealName(active.id), column: columnName(over.id) }) : undefined,
            onDragEnd: ({ active, over }) =>
                over
                    ? t('a11yDropped', { name: dealName(active.id), column: columnName(over.id) })
                    : t('a11yCancelled', { name: dealName(active.id) }),
            onDragCancel: ({ active }) => t('a11yCancelled', { name: dealName(active.id) }),
        }),
        [t, dealName, columnName],
    );
    const screenReaderInstructions: ScreenReaderInstructions = useMemo(
        () => ({ draggable: t('a11yInstructions') }),
        [t],
    );

    if (selectedPipelineId == null || columns.length === 0) {
        return (
            <div className="rounded-2xl border border-dashed border-border px-6 py-12 text-center text-sm text-muted-foreground">
                {t('noPipeline')}
            </div>
        );
    }

    const selectedPipeline = pipelineById.get(selectedPipelineId);

    return (
        <div className="flex flex-col gap-3">
            {pipelineOptions.length > 1 && (
                <div className="flex items-center gap-2">
                    <span className="text-sm text-muted-foreground">{t('pipelineLabel')}</span>
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                aria-label={t('pipelineLabel')}
                                className="flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-sm text-foreground ring-1 ring-border transition active:scale-[0.98] hover:bg-muted/80"
                            >
                                {selectedPipeline?.name ?? '—'}
                                <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start">
                            {pipelineOptions.map((p) => (
                                <DropdownMenuItem key={p.id} onSelect={() => setSelected(p.id)}>
                                    <span className={p.id === selectedPipelineId ? 'font-semibold' : ''}>{p.name}</span>
                                </DropdownMenuItem>
                            ))}
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            )}

            <KanbanBoard<Deal>
                columns={columns}
                items={boardDeals}
                getId={(d) => d.id}
                getColumnId={(d) => String(d.stage)}
                getPosition={(d) => d.position}
                renderCard={renderCard}
                onMove={onMove}
                reduce={reduce}
                emptyHint={t('emptyColumn')}
                countLabel={(count) => t('count', { count })}
                accessibility={{ announcements, screenReaderInstructions }}
            />
        </div>
    );
}
