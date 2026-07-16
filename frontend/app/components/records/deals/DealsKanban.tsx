'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import type { UniqueIdentifier } from '@dnd-kit/core';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import { kanbanAccessibility } from '@/app/components/kanban/kanbanAccessibility';
import DealKanbanCard from '@/app/components/records/deals/DealKanbanCard';
import { classifyStage } from './dealOutcome';
import { getCompaniesByIds, getDealBoard, getDealRisks, moveDeal } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import type { Company, Deal, DealFilterParams, DealRisk, Pipeline, Stage } from '@/app/lib/types';

interface DealsKanbanProps {
    deals: Deal[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    companyById: Map<number, Company>;
    pipelineById: Map<number, Pipeline>;
    stageById: Map<number, Stage>;
    riskByDealId: Map<number, DealRisk>;
    onQuickEdit: (deal: Deal) => void;
    onDelete: (deal: Deal) => void;
    onMoved: () => void;
    query: string;
    currency?: string;
    filters: DealFilterParams;
    revision: number;
    reduce: boolean;
}

const RISK_BATCH_SIZE = 100;

async function loadBoardRisks(deals: Deal[]): Promise<DealRisk[]> {
    const risks: DealRisk[] = [];
    for (let offset = 0; offset < deals.length; offset += RISK_BATCH_SIZE) {
        risks.push(...await getDealRisks(deals.slice(offset, offset + RISK_BATCH_SIZE).map((deal) => deal.id)));
    }
    return risks;
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
    riskByDealId,
    onQuickEdit,
    onDelete,
    onMoved,
    query,
    currency,
    filters,
    revision,
    reduce,
}: DealsKanbanProps) {
    const t = useTranslations('DealsKanban');

    const pipelineOptions = useMemo(
        () => pipelines.filter((pipeline) =>
            (stagesByPipeline[pipeline.id]?.length ?? 0) > 0
            && (!filters.pipelineId?.length || filters.pipelineId.includes(pipeline.id))),
        [filters.pipelineId, pipelines, stagesByPipeline],
    );

    const defaultPipelineId = useMemo(() => {
        const counts = new Map<number, number>();
        for (const d of deals) if (d.pipeline != null) counts.set(d.pipeline, (counts.get(d.pipeline) ?? 0) + 1);
        let best: number | null = null;
        let bestCount = -1;
        for (const [pid, n] of counts) {
            if (n > bestCount && pipelineOptions.some((pipeline) => pipeline.id === pid)) {
                best = pid;
                bestCount = n;
            }
        }
        return best ?? pipelineOptions[0]?.id ?? null;
    }, [deals, pipelineOptions]);

    const [selected, setSelected] = useState<number | null>(null);
    const selectedPipelineId =
        selected != null && pipelineOptions.some((pipeline) => pipeline.id === selected)
            ? selected
            : defaultPipelineId;
    const [boardRevision, setBoardRevision] = useState(0);
    const scopeKey = `${filters.scope ?? ''}:${filters.memberIds?.join(',') ?? ''}`;
    const boardKey = selectedPipelineId == null ? null : `${selectedPipelineId}:${boardRevision}:${revision}:${scopeKey}`;
    const [boardState, setBoardState] = useState<{
        key: string | null;
        deals: Deal[];
        error: string | null;
    }>({ key: null, deals: [], error: null });

    useEffect(() => {
        if (selectedPipelineId == null || boardKey == null) return;
        let cancelled = false;
        getDealBoard(selectedPipelineId, { scope: filters.scope, memberIds: filters.memberIds })
            .then((loaded) => {
                if (!cancelled) setBoardState({ key: boardKey, deals: loaded, error: null });
            })
            .catch((error: unknown) => {
                if (cancelled) return;
                setBoardState({
                    key: boardKey,
                    deals: [],
                    error: error instanceof Error ? error.message : t('loadFailed'),
                });
            });
        return () => {
            cancelled = true;
        };
    }, [boardKey, selectedPipelineId, filters.scope, filters.memberIds, t]);

    const boardLoading = boardKey != null && boardState.key !== boardKey;
    const boardError = boardState.key === boardKey ? boardState.error : null;
    const loadedBoardDeals = useMemo(
        () => boardState.key === boardKey ? boardState.deals : [],
        [boardKey, boardState],
    );
    const [boardCompanies, setBoardCompanies] = useState<Map<number, Company>>(new Map());

    useEffect(() => {
        if (boardState.key !== boardKey) return;
        let cancelled = false;
        getCompaniesByIds(loadedBoardDeals.flatMap((deal) => deal.company == null ? [] : [deal.company]))
            .then((loaded) => {
                if (!cancelled) setBoardCompanies(new Map(loaded.map((company) => [company.id, company])));
            })
            .catch(() => {
                if (!cancelled) {
                    setBoardCompanies(new Map());
                    toastError(t('companyLoadFailed'));
                }
            });
        return () => {
            cancelled = true;
        };
    }, [boardKey, boardState.key, loadedBoardDeals, t]);

    const resolvedCompanyById = useMemo(() => {
        const resolved = new Map(companyById);
        boardCompanies.forEach((company, id) => resolved.set(id, company));
        return resolved;
    }, [boardCompanies, companyById]);
    const [boardRiskState, setBoardRiskState] = useState<{
        key: string | null;
        risks: Map<number, DealRisk>;
        error: string | null;
    }>({ key: null, risks: new Map(), error: null });

    useEffect(() => {
        if (boardKey == null || boardState.key !== boardKey) return;
        let cancelled = false;
        loadBoardRisks(loadedBoardDeals)
            .then((risks) => {
                if (!cancelled) {
                    setBoardRiskState({
                        key: boardKey,
                        risks: new Map(risks.map((risk) => [risk.dealId, risk])),
                        error: null,
                    });
                }
            })
            .catch((error: unknown) => {
                if (!cancelled) {
                    setBoardRiskState({
                        key: boardKey,
                        risks: new Map(),
                        error: error instanceof Error ? error.message : t('riskLoadFailed'),
                    });
                }
            });
        return () => {
            cancelled = true;
        };
    }, [boardKey, boardState.key, loadedBoardDeals, t]);

    const boardRisks = useMemo(() => {
        const combined = new Map(riskByDealId);
        if (boardRiskState.key === boardKey) {
            boardRiskState.risks.forEach((risk, dealId) => combined.set(dealId, risk));
        }
        return combined;
    }, [boardKey, boardRiskState, riskByDealId]);
    const boardRiskLoading = Boolean(filters.risk?.length) && boardRiskState.key !== boardKey;
    const boardRiskError = boardRiskState.key === boardKey ? boardRiskState.error : null;

    const matchesBoardDeal = useCallback((deal: Deal) => {
        const normalizedQuery = query.trim().toLocaleLowerCase();
        if (normalizedQuery) {
            const values = [
                deal.name,
                deal.currency,
                deal.company != null ? resolvedCompanyById.get(deal.company)?.name : undefined,
                deal.pipeline != null ? pipelineById.get(deal.pipeline)?.name : undefined,
                deal.stage != null ? stageById.get(deal.stage)?.name : undefined,
            ];
            if (!values.some((value) => value?.toLocaleLowerCase().includes(normalizedQuery))) return false;
        }
        if (currency && deal.currency !== currency) return false;
        if (filters.pipelineId?.length && (deal.pipeline == null || !filters.pipelineId.includes(deal.pipeline))) return false;
        if (filters.stageId?.length && (deal.stage == null || !filters.stageId.includes(deal.stage))) return false;
        if (filters.companyId?.length || filters.noCompany) {
            const companyMatches = deal.company != null && filters.companyId?.includes(deal.company);
            if (!companyMatches && !(filters.noCompany && deal.company == null)) return false;
        }
        if (filters.status?.length) {
            const statusMatches = filters.status.some((status) =>
                status === 'open' && deal.won == null
                || status === 'closed' && deal.won != null
                || status === 'won' && deal.won === true
                || status === 'lost' && deal.won === false);
            if (!statusMatches) return false;
        }
        if (filters.risk?.length) {
            const level = boardRisks.get(deal.id)?.level ?? 'none';
            if (!filters.risk.includes(level)) return false;
        }
        return true;
    }, [boardRisks, currency, filters, pipelineById, query, resolvedCompanyById, stageById]);

    const columns: KanbanColumnDef[] = useMemo(() => {
        const stages = selectedPipelineId != null ? stagesByPipeline[selectedPipelineId] ?? [] : [];
        return [...stages]
            .filter((stage) => !filters.stageId?.length || filters.stageId.includes(stage.id))
            .sort((a, b) => a.position - b.position)
            .map((s) => ({ id: String(s.id), label: s.name, accent: stageAccent(s) }));
    }, [filters.stageId, selectedPipelineId, stagesByPipeline]);

    const boardDeals = useMemo(
        () => loadedBoardDeals.filter((deal) =>
            deal.pipeline === selectedPipelineId && matchesBoardDeal(deal)),
        [loadedBoardDeals, matchesBoardDeal, selectedPipelineId],
    );

    const dealsById = useMemo(() => new Map(boardDeals.map((d) => [d.id, d])), [boardDeals]);

    const renderCard = useCallback(
        (deal: Deal) => (
            <DealKanbanCard
                deal={deal}
                company={deal.company != null ? resolvedCompanyById.get(deal.company) : undefined}
                risk={boardRisks.get(deal.id)}
                onQuickEdit={() => onQuickEdit(deal)}
                onDelete={() => onDelete(deal)}
            />
        ),
        [boardRisks, onQuickEdit, onDelete, resolvedCompanyById],
    );

    const onMove = useCallback(
        async (dealId: number, colId: string, index: number) => {
            try {
                const stageId = Number(colId);
                const absoluteStageDeals = loadedBoardDeals
                    .filter((deal) => deal.stage === stageId && deal.id !== dealId)
                    .sort((left, right) => left.position - right.position || left.id - right.id);
                const visibleStageDeals = absoluteStageDeals.filter(matchesBoardDeal);
                const nextVisible = visibleStageDeals[index];
                const previousVisible = index > 0 ? visibleStageDeals[index - 1] : undefined;
                const absoluteIndex = nextVisible
                    ? absoluteStageDeals.findIndex((deal) => deal.id === nextVisible.id)
                    : previousVisible
                      ? absoluteStageDeals.findIndex((deal) => deal.id === previousVisible.id) + 1
                      : absoluteStageDeals.length;
                await moveDeal(dealId, stageId, absoluteIndex);
                setBoardRevision((revision) => revision + 1);
                onMoved();
            } catch (err) {
                toastError(err instanceof Error ? err.message : t('moveFailed'));
                throw err;
            }
        },
        [loadedBoardDeals, matchesBoardDeal, onMoved, t],
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

    const { announcements, screenReaderInstructions } = useMemo(
        () => kanbanAccessibility(t, dealName, columnName),
        [t, dealName, columnName],
    );

    if (selectedPipelineId == null || columns.length === 0) {
        return (
            <div className="rounded-2xl border border-dashed border-border px-6 py-12 text-center text-sm text-muted-foreground">
                {t('noPipeline')}
            </div>
        );
    }

    const selectedPipeline = pipelineById.get(selectedPipelineId);
    const boardContent = boardLoading || boardRiskLoading ? (
        <div aria-busy="true" className="rounded-2xl border border-border px-6 py-12 text-center text-sm text-muted-foreground">
            {t('loading')}
        </div>
    ) : boardError || (filters.risk?.length && boardRiskError) ? (
        <div role="alert" className="rounded-2xl border border-destructive/30 bg-destructive/5 px-6 py-12 text-center text-sm text-destructive">
            {boardError ?? boardRiskError}
        </div>
    ) : (
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
    );

    return (
        <div className="flex flex-col gap-3">
            {boardRiskError && !filters.risk?.length ? (
                <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                    {boardRiskError}
                </div>
            ) : null}
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

            {boardContent}
        </div>
    );
}
