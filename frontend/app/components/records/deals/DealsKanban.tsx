'use client';

import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { UniqueIdentifier } from '@dnd-kit/core';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import { kanbanAccessibility } from '@/app/components/kanban/kanbanAccessibility';
import DealKanbanCard from '@/app/components/records/deals/DealKanbanCard';
import { classifyStage, dealOutcome } from './dealOutcome';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { usePermission } from '@/app/hooks/usePermissions';
import { getCompaniesByIds, getDealBoard, getDealById, getDealRisks, moveDeal, reopenDeal } from '@/app/lib/api';
import { withDealMoved } from '@/app/lib/dealBoard';
import { getNumberFormatter } from '@/app/lib/intlFormatters';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Company, Deal, DealFilterParams, DealRisk, Pipeline, Stage } from '@/app/lib/types';
import type { RecordReturnSelectionSnapshot } from '@/app/lib/recordReturnPath';

interface DealsKanbanProps {
    timezone: string;
    deals: Deal[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    companyById: Map<number, Company>;
    pipelineById: Map<number, Pipeline>;
    stageById: Map<number, Stage>;
    riskByDealId: Map<number, DealRisk>;
    onQuickEdit?: (deal: Deal) => void;
    onDelete?: (deal: Deal) => void;
    onMoved: () => void;
    query: string;
    currency?: string;
    filters: DealFilterParams;
    segmentActive: boolean;
    segmentIds: ReadonlySet<number> | null;
    segmentLoading: boolean;
    segmentError: string | null;
    currentUserId: number;
    revision: number;
    reduce: boolean;
    returnSelection?: RecordReturnSelectionSnapshot;
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

function dealId(deal: Deal): number {
    return deal.id;
}

function dealColumnId(deal: Deal): string {
    return String(deal.stage);
}

function dealPosition(deal: Deal): number {
    return deal.position;
}

export default function DealsKanban({
    timezone,
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
    segmentActive,
    segmentIds,
    segmentLoading,
    segmentError,
    currentUserId,
    revision,
    reduce,
    returnSelection,
}: DealsKanbanProps) {
    const t = useTranslations('DealsKanban');
    const locale = useLocale();
    const showApiError = useApiErrorToast('DealsKanban');
    const canUpdateDeals = usePermission('DEAL_UPDATE');
    const [pendingMove, setPendingMove] = useState<{ name: string; column: string; outcome: 'won' | 'lost'; valueDisclosure: string } | null>(null);
    const confirmMoveRef = useRef<((confirmed: boolean) => void) | null>(null);
    const cancelMoveRef = useRef<HTMLButtonElement>(null);
    const [moveMessage, setMoveMessage] = useState('');
    const [moveResult, setMoveResult] = useState<{ deal: Deal; workspaceId: number | null } | null>(null);
    const [reopening, setReopening] = useState(false);
    const boardContainerRef = useRef<HTMLDivElement>(null);
    const moveWorkspaceRef = useRef<number | null>(null);

    useEffect(() => () => confirmMoveRef.current?.(false), []);

    const resolveMove = (confirmed: boolean) => {
        confirmMoveRef.current?.(confirmed);
        confirmMoveRef.current = null;
        setPendingMove(null);
    };

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
    const boardKey = selectedPipelineId == null ? null : `${selectedPipelineId}:${revision}`;
    const loadedPipelineRef = useRef<number | null>(null);
    const [boardState, setBoardState] = useState<{
        key: string | null;
        pipelineId: number | null;
        deals: Deal[];
        error: string | null;
    }>({ key: null, pipelineId: null, deals: [], error: null });

    useEffect(() => {
        if (selectedPipelineId == null || boardKey == null) return;
        let cancelled = false;
        getDealBoard(selectedPipelineId)
            .then((loaded) => {
                if (cancelled) return;
                loadedPipelineRef.current = selectedPipelineId;
                setBoardState({ key: boardKey, pipelineId: selectedPipelineId, deals: loaded, error: null });
            })
            .catch(() => {
                if (cancelled) return;
                if (loadedPipelineRef.current === selectedPipelineId) {
                    toastError(t('refreshFailed'));
                    setBoardState((current) => ({ ...current, key: boardKey }));
                    return;
                }
                setBoardState({ key: boardKey, pipelineId: selectedPipelineId, deals: [], error: t('loadFailed') });
            });
        return () => {
            cancelled = true;
        };
    }, [boardKey, selectedPipelineId, t]);

    const boardShowsSelectedPipeline = boardState.pipelineId === selectedPipelineId && boardState.error === null;
    const boardLoading = boardKey != null && boardState.key !== boardKey && !boardShowsSelectedPipeline;
    const boardError = boardState.key === boardKey ? boardState.error : null;
    const loadedBoardDeals = useMemo(
        () => boardShowsSelectedPipeline ? boardState.deals : [],
        [boardShowsSelectedPipeline, boardState],
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
        pipelineId: number | null;
        risks: Map<number, DealRisk>;
        error: string | null;
    }>({ key: null, pipelineId: null, risks: new Map(), error: null });

    useEffect(() => {
        if (boardKey == null || boardState.key !== boardKey) return;
        let cancelled = false;
        loadBoardRisks(loadedBoardDeals)
            .then((risks) => {
                if (!cancelled) {
                    setBoardRiskState({
                        key: boardKey,
                        pipelineId: selectedPipelineId,
                        risks: new Map(risks.map((risk) => [risk.dealId, risk])),
                        error: null,
                    });
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setBoardRiskState({
                        key: boardKey,
                        pipelineId: selectedPipelineId,
                        risks: new Map(),
                        error: t('riskLoadFailed'),
                    });
                }
            });
        return () => {
            cancelled = true;
        };
    }, [boardKey, boardState.key, loadedBoardDeals, selectedPipelineId, t]);

    const boardRisks = useMemo(() => {
        const combined = new Map(riskByDealId);
        boardRiskState.risks.forEach((risk, dealId) => combined.set(dealId, risk));
        return combined;
    }, [boardRiskState, riskByDealId]);
    const boardRiskLoading = Boolean(filters.risk?.length) && boardRiskState.pipelineId !== selectedPipelineId;
    const boardRiskError = boardRiskState.key === boardKey ? boardRiskState.error : null;

    const matchesBoardDeal = useCallback((deal: Deal) => {
        if (segmentActive && !segmentIds?.has(deal.id)) return false;
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
        if (filters.scope === 'me' && deal.ownerId !== currentUserId) return false;
        if (filters.scope === 'unassigned' && deal.ownerId != null) return false;
        if (filters.scope === 'members'
            && (deal.ownerId == null || !filters.memberIds?.includes(deal.ownerId))) return false;
        return true;
    }, [boardRisks, currency, currentUserId, filters, pipelineById, query, resolvedCompanyById, segmentActive, segmentIds, stageById]);

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
    const [preparingQuickEdit, setPreparingQuickEdit] = useState(false);
    const prepareQuickEdit = useCallback(async (deal: Deal) => {
        if (!onQuickEdit) return;
        setPreparingQuickEdit(true);
        try {
            const originWorkspaceId = renderedWorkspaceId();
            const current = await getDealById(deal.id, workspacePinnedRequest(originWorkspaceId));
            if (renderedWorkspaceId() === originWorkspaceId) onQuickEdit(current);
        } catch (error) {
            showApiError(error, 'loadFailed');
        } finally {
            setPreparingQuickEdit(false);
        }
    }, [onQuickEdit, showApiError]);

    const renderCard = useCallback(
        (deal: Deal, dragHandle?: ReactNode) => (
            <DealKanbanCard
                timezone={timezone}
                deal={deal}
                company={deal.company != null ? resolvedCompanyById.get(deal.company) : undefined}
                risk={boardRisks.get(deal.id)}
                onQuickEdit={onQuickEdit ? () => prepareQuickEdit(deal) : undefined}
                onDelete={onDelete ? () => onDelete(deal) : undefined}
                returnSelection={returnSelection}
                dragHandle={dragHandle}
            />
        ),
        [boardRisks, onQuickEdit, prepareQuickEdit, onDelete, resolvedCompanyById, returnSelection, timezone],
    );

    const beforeMove = useCallback(
        async (dealId: number, colId: string) => {
            const deal = dealsById.get(dealId);
            const stage = stageById.get(Number(colId));
            if (!deal || !stage) return false;
            const originWorkspaceId = renderedWorkspaceId();
            moveWorkspaceRef.current = originWorkspaceId;
            setMoveResult(null);
            setMoveMessage('');
            const outcome = classifyStage(stage);
            if (deal.stage !== stage.id && outcome !== 'normal') {
                const money = (value: number) => `${getNumberFormatter(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)} ${deal.currency}`;
                const valueDisclosure = outcome === 'lost'
                    ? t('confirmValueLost', { current: money(deal.actualValue), amount: money(0) })
                    : deal.won === true
                      ? t('confirmValueFrozen', { amount: money(deal.actualValue) })
                      : t('confirmValueFreshWin', { current: money(deal.value), currency: deal.currency });
                const confirmed = await new Promise<boolean>((resolve) => {
                    confirmMoveRef.current = resolve;
                    setPendingMove({ name: deal.name, column: stage.name, outcome, valueDisclosure });
                });
                if (!confirmed) return false;
            }
            return renderedWorkspaceId() === originWorkspaceId;
        },
        [dealsById, locale, stageById, t],
    );

    const onMove = useCallback(
        async (dealId: number, colId: string, index: number) => {
            const deal = dealsById.get(dealId);
            const stage = stageById.get(Number(colId));
            const originWorkspaceId = moveWorkspaceRef.current;
            if (!deal || !stage || renderedWorkspaceId() !== originWorkspaceId) return false;
            const outcome = classifyStage(stage);
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
                const moved = await moveDeal(dealId, stageId, absoluteIndex, workspacePinnedRequest(originWorkspaceId));
                if (renderedWorkspaceId() !== originWorkspaceId) return false;
                setBoardState((current) => current.pipelineId !== selectedPipelineId
                    ? current
                    : { ...current, deals: withDealMoved(current.deals, moved) });
                onMoved();
                const message = t(moved.won != null && outcome === 'normal' && deal.stage !== moved.stage ? 'movedRetained' : 'moved', {
                    name: moved.name,
                    column: stageById.get(moved.stage ?? -1)?.name ?? stage.name,
                    outcome: t(`outcome.${dealOutcome(moved.won)}`),
                    position: moved.position + 1,
                });
                setMoveMessage(message);
                setMoveResult({ deal: moved, workspaceId: originWorkspaceId });
                toastSuccess(message);
                const visibleDestination = withDealMoved(loadedBoardDeals, moved)
                    .filter((deal) => deal.stage === moved.stage && matchesBoardDeal(deal))
                    .sort((left, right) => left.position - right.position || left.id - right.id);
                return { columnId: String(moved.stage), index: visibleDestination.findIndex((deal) => deal.id === moved.id) };
            } catch (err) {
                showApiError(err, 'moveFailed');
                throw err;
            }
        },
        [dealsById, loadedBoardDeals, matchesBoardDeal, onMoved, selectedPipelineId, showApiError, stageById, t],
    );

    const reopen = async () => {
        if (!moveResult || renderedWorkspaceId() !== moveResult.workspaceId) return;
        setReopening(true);
        try {
            const reopened = await reopenDeal(moveResult.deal.id, workspacePinnedRequest(moveResult.workspaceId));
            if (renderedWorkspaceId() !== moveResult.workspaceId) return;
            setBoardState((current) => ({ ...current, deals: withDealMoved(current.deals, reopened) }));
            setMoveResult(null);
            const message = t('moved', { name: reopened.name, column: stageById.get(reopened.stage ?? -1)?.name ?? '', position: reopened.position + 1, outcome: t('outcome.open') });
            setMoveMessage(message);
            toastSuccess(message);
            onMoved();
            requestAnimationFrame(() => boardContainerRef.current?.querySelector<HTMLButtonElement>(`[data-kanban-drag-handle="${reopened.id}"]`)?.focus());
        } catch (error) {
            showApiError(error, 'reopenFailed');
        } finally {
            setReopening(false);
        }
    };

    const dealName = useCallback((id: UniqueIdentifier) => dealsById.get(Number(id))?.name ?? '', [dealsById]);
    const columnName = useCallback(
        (id: string) => stageById.get(Number(id))?.name ?? '',
        [stageById],
    );
    const dropped = useCallback((id: UniqueIdentifier, columnId: string) => {
        const stage = stageById.get(Number(columnId));
        const deal = dealsById.get(Number(id));
        const stageOutcome = classifyStage(stage);
        const outcome = stageOutcome === 'normal' ? dealOutcome(deal?.won) : stageOutcome;
        return t(stageOutcome !== 'normal' && deal?.stage !== stage?.id ? 'a11yConfirmMove' : 'a11yMoveRequested', {
            name: dealName(id), column: columnName(columnId), outcome: t(`outcome.${outcome}`),
        });
    }, [columnName, dealName, dealsById, stageById, t]);

    const { announcements, screenReaderInstructions, moveRestored } = useMemo(
        () => kanbanAccessibility(t, dealName, columnName, dropped),
        [t, dealName, columnName, dropped],
    );

    if (selectedPipelineId == null || columns.length === 0) {
        return (
            <div className="rounded-2xl border border-dashed border-border px-6 py-12 text-center text-sm text-muted-foreground">
                {t('noPipeline')}
            </div>
        );
    }

    const selectedPipeline = pipelineById.get(selectedPipelineId);
    const boardContent = boardLoading || boardRiskLoading || segmentLoading || preparingQuickEdit ? (
        <div aria-busy="true" className="rounded-2xl border border-border px-6 py-12 text-center text-sm text-muted-foreground">
            {t('loading')}
        </div>
    ) : boardError || segmentError || (filters.risk?.length && boardRiskError) ? (
        <div role="alert" className="rounded-2xl border border-destructive/30 bg-destructive/5 px-6 py-12 text-center text-sm text-destructive">
            {boardError ?? segmentError ?? boardRiskError}
        </div>
    ) : (
        <KanbanBoard<Deal>
            columns={columns}
            items={boardDeals}
            getId={dealId}
            getColumnId={dealColumnId}
            getPosition={dealPosition}
            renderCard={renderCard}
            dragLabel={(deal) => t('moveCard', { name: deal.name })}
            onBeforeMove={canUpdateDeals ? beforeMove : undefined}
            onMove={canUpdateDeals ? onMove : undefined}
            reduce={reduce}
            emptyHint={t('emptyColumn')}
            countLabel={(count) => t('count', { count })}
            accessibility={{ announcements, screenReaderInstructions, moveRestored }}
        />
    );

    return (
        <div ref={boardContainerRef} className="flex flex-col gap-3">
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
            <div role="status" aria-live="polite" aria-atomic="true" className="flex flex-wrap items-center gap-2 text-sm">
                {moveMessage}
            </div>
            {canUpdateDeals && moveResult?.deal.won != null && (
                <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span>{t('reopenHint')}</span>
                    <Button variant="outline" size="inline" disabled={reopening} aria-busy={reopening} onClick={reopen}>{t('reopen')}</Button>
                </div>
            )}
            <Dialog open={pendingMove !== null} onOpenChange={(open) => { if (!open) resolveMove(false); }}>
                <DialogContent
                    size="sm"
                    onOpenAutoFocus={(event) => { event.preventDefault(); cancelMoveRef.current?.focus(); }}
                    onCloseAutoFocus={(event) => event.preventDefault()}
                >
                    <DialogHeader>
                        <DialogTitle>{t('confirmTitle', { column: pendingMove?.column ?? '' })}</DialogTitle>
                        <DialogDescription>{pendingMove ? <>
                            {t('confirmDescription', { name: pendingMove.name, column: pendingMove.column, outcome: t(`outcome.${pendingMove.outcome}`) })}
                            <span className="mt-3 block">{pendingMove.valueDisclosure}</span>
                        </> : ''}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <Button ref={cancelMoveRef} variant="outline" onClick={() => resolveMove(false)}>{t('cancelMove')}</Button>
                        <Button onClick={() => resolveMove(true)}>{t('confirmMove')}</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
