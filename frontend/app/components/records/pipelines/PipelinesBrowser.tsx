'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastInfo, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { PlusIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon, ShareIcon } from '@heroicons/react/24/solid';
import {
    Squares2X2Icon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { SearchField, FilterBar, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import ShareDialog from '@/app/components/records/ShareDialog';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { type ColumnDef } from '@/app/components/records/types';
import PipelineCard from '@/app/components/records/pipelines/PipelineCard';
import NewPipelineDialog from '@/app/components/records/pipelines/NewPipelineDialog';
import QuickEditPipelineSheet, { type PipelineDraft, type PipelineStageDraft, type StageKind } from '@/app/components/records/pipelines/QuickEditPipelineSheet';
import {
    createPipeline,
    createStage,
    deletePipeline,
    deleteStage,
    getActivities,
    getDeals,
    getNotes,
    getStagesByPipelineId,
    getTasks,
    getUsers,
    isFieldError,
    updatePipeline,
    updateStage,
} from '@/app/lib/api';
import { parseMysqlDateTime } from '@/app/lib/utils';
import type {
    Activity,
    CreatePipelinePayload,
    Deal,
    LoadStatus,
    Note,
    Pipeline,
    PipelineMetrics,
    Stage,
    StageMetrics,
    Task,
    UpdatePipelinePayload,
    User,
} from '@/app/lib/types';

function toDraft(p: Pipeline, stages: Stage[] = []): PipelineDraft {
    return {
        name: p.name ?? '',
        stages: [...stages]
            .sort((a, b) => a.position - b.position)
            .map((s) => ({ id: s.id, name: s.name, success: s.success, failure: s.failure })),
    };
}

function diffDraft(a: PipelineDraft, b: PipelineDraft): boolean {
    if (a.name !== b.name) return true;
    if (a.stages.length !== b.stages.length) return true;
    for (let i = 0; i < a.stages.length; i++) {
        if (a.stages[i].id !== b.stages[i].id) return true;
        if (a.stages[i].name !== b.stages[i].name) return true;
        if (a.stages[i].success !== b.stages[i].success) return true;
        if (a.stages[i].failure !== b.stages[i].failure) return true;
    }
    return false;
}

const searchFields = (p: Pipeline) => [p.name];

export default function PipelinesBrowser({ pipelines }: { pipelines: Pipeline[] }) {
    const router = useRouter();
    const t = useTranslations('PipelinesBrowser');
    const tf = useTranslations('Filters');
    const reduce = useReducedMotion() ?? false;
    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredPipelines,
        selectedItems: selectedPipelines,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Pipeline>({
        items: pipelines,
        storageKey: 'pipelines:view',
        searchFields,
    });

    const [isDeleting, setIsDeleting] = useState(false);
    const [shareOpen, setShareOpen] = useState(false);
    const { activeWorkspaceId } = useWorkspace();
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, PipelineDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const emptyPipelineDraft: CreatePipelinePayload = { name: '' };
    const [newPipelineDialogOpen, setNewPipelineDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newPipelinePayload, setNewPipelinePayload] = useState<CreatePipelinePayload>(emptyPipelineDraft);

    const [stagesByPipeline, setStagesByPipeline] = useState<Map<number, Stage[]>>(new Map());
    const [allDeals, setAllDeals] = useState<Deal[]>([]);
    const [allTasks, setAllTasks] = useState<Task[]>([]);
    const [allActivities, setAllActivities] = useState<Activity[]>([]);
    const [allNotes, setAllNotes] = useState<Note[]>([]);
    const [allUsers, setAllUsers] = useState<User[]>([]);
    const [metricsStatus, setMetricsStatus] = useState<LoadStatus>('idle');

    const ensureMetricsLoaded = useCallback(() => {
        if (metricsStatus === 'loading' || metricsStatus === 'ready') return;
        setMetricsStatus('loading');
        Promise.all([
            Promise.all(pipelines.map((p) => getStagesByPipelineId(p.id).then((stages) => [p.id, stages] as const))),
            getDeals(),
            getTasks(),
            getActivities(),
            getNotes(),
            getUsers(),
        ])
            .then(([stagesPairs, deals, tasks, activities, notes, users]) => {
                setStagesByPipeline(new Map(stagesPairs));
                setAllDeals(deals);
                setAllTasks(tasks);
                setAllActivities(activities);
                setAllNotes(notes);
                setAllUsers(users);
                setMetricsStatus('ready');
            })
            .catch((err) => {
                console.error(err);
                setMetricsStatus('error');
                toastError(t('failedToLoadMetrics'));
            });
    }, [metricsStatus, pipelines, t]);

    const closeNewPipelineDialog = (open: boolean) => {
        setNewPipelineDialogOpen(open);
        if (!open) {
            setNewPipelinePayload(emptyPipelineDraft);
            setCreationSucceeded(false);
        }
    };

    const createNewPipeline = async () => {
        const { stages: rawStages, ...pipelineFields } = newPipelinePayload;
        const validStages = (rawStages ?? [])
            .map((s) => ({ ...s, name: s.name.trim() }))
            .filter((s) => s.name.length > 0);

        if (validStages.filter((s) => s.success).length > 1 || validStages.filter((s) => s.failure).length > 1) {
            toastError(t('singleTerminalPerType'));
            return;
        }

        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const created = await createPipeline(pipelineFields);

            if (validStages.length > 0) {
                const results = await Promise.allSettled(
                    validStages.map((s, position) =>
                        createStage(created.id, { name: s.name, position, success: s.success ?? false, failure: s.failure ?? false }),
                    ),
                );
                const failed = results.filter((r) => r.status === 'rejected').length;
                if (failed > 0) {
                    console.error('Some stages failed to create', results);
                    toastError(t('pipelineCreatedWithFailedStages', { failed, total: validStages.length }));
                } else {
                    toastSuccess(t('pipelineCreated'));
                }
            } else {
                toastSuccess(t('pipelineCreated'));
            }

            setIsCreating(false);
            setCreationSucceeded(true);
            setMetricsStatus('idle');
            setTimeout(() => {
                closeNewPipelineDialog(false);
                router.refresh();
            }, 900);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            console.error(err);
            toastError(t('failedToCreate'));
        } finally {
            setIsCreating(false);
        }
    };

    const fetchStagesIfMissing = useCallback(async (pipelineIds: number[]): Promise<Map<number, Stage[]>> => {
        const missing = pipelineIds.filter((id) => !stagesByPipeline.has(id));
        if (missing.length === 0) return stagesByPipeline;
        const fetched = await Promise.all(
            missing.map((id) => getStagesByPipelineId(id).then((stages) => [id, stages] as const)),
        );
        const merged = new Map(stagesByPipeline);
        for (const [id, stages] of fetched) merged.set(id, stages);
        setStagesByPipeline(merged);
        return merged;
    }, [stagesByPipeline]);

    const openEditSheet = async () => {
        const map = await fetchStagesIfMissing(selectedPipelines.map((p) => p.id));
        const next: Record<number, PipelineDraft> = {};
        for (const p of selectedPipelines) next[p.id] = toDraft(p, map.get(p.id) ?? []);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<PipelineDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const updateStageName = (pipelineId: number, index: number, name: string) => {
        setDrafts((prev) => {
            const current = prev[pipelineId];
            if (!current) return prev;
            const stages = current.stages.map((s, i) => (i === index ? { ...s, name } : s));
            return { ...prev, [pipelineId]: { ...current, stages } };
        });
    };

    const updateStageKind = (pipelineId: number, index: number, kind: StageKind) => {
        setDrafts((prev) => {
            const current = prev[pipelineId];
            if (!current) return prev;
            const stages = current.stages.map((s, i) =>
                i === index ? { ...s, success: kind === 'won', failure: kind === 'lost' } : s,
            );
            return { ...prev, [pipelineId]: { ...current, stages } };
        });
    };

    const addStage = (pipelineId: number) => {
        setDrafts((prev) => {
            const current = prev[pipelineId];
            if (!current) return prev;
            return {
                ...prev,
                [pipelineId]: { ...current, stages: [...current.stages, { id: null, name: '', success: false, failure: false }] },
            };
        });
    };

    const removeStage = (pipelineId: number, index: number) => {
        setDrafts((prev) => {
            const current = prev[pipelineId];
            if (!current) return prev;
            return {
                ...prev,
                [pipelineId]: { ...current, stages: current.stages.filter((_, i) => i !== index) },
            };
        });
    };

    const saveEdits = async () => {
        const changed = selectedPipelines.filter((p) => {
            const draft = drafts[p.id];
            const original = toDraft(p, stagesByPipeline.get(p.id) ?? []);
            return draft && diffDraft(original, draft);
        });

        if (changed.length === 0) {
            toastInfo(t('noChangesToSave'));
            setEditSheetOpen(false);
            return;
        }

        const invalidName = changed.find((p) => !drafts[p.id].name.trim());
        if (invalidName) {
            toastError(t('nameRequiredFor', { name: invalidName.name }));
            return;
        }

        const invalidStage = changed.find((p) =>
            drafts[p.id].stages.some((s) => !s.name.trim()),
        );
        if (invalidStage) {
            toastError(t('stageNamesEmptyIn', { name: invalidStage.name }));
            return;
        }

        const invalidTerminal = changed.find((p) =>
            drafts[p.id].stages.filter((s) => s.success).length > 1 ||
            drafts[p.id].stages.filter((s) => s.failure).length > 1,
        );
        if (invalidTerminal) {
            toastError(t('singleTerminalPerTypeIn', { name: invalidTerminal.name }));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map(async (p) => {
                    const draft = drafts[p.id];
                    const original = toDraft(p, stagesByPipeline.get(p.id) ?? []);
                    const originalStages = stagesByPipeline.get(p.id) ?? [];

                    if (original.name !== draft.name) {
                        const payload: UpdatePipelinePayload = { name: draft.name.trim() };
                        await updatePipeline(p.id, payload);
                    }

                    const draftIds = new Set(
                        draft.stages.filter((s) => s.id !== null).map((s) => s.id as number),
                    );
                    const toDelete = originalStages.filter((s) => !draftIds.has(s.id));
                    const originalById = new Map(originalStages.map((s) => [s.id, s]));

                    for (const stage of toDelete) {
                        await deleteStage(stage.id);
                    }

                    const maxOriginalPosition = originalStages.reduce(
                        (max, s) => Math.max(max, s.position),
                        -1,
                    );
                    let nextNewPosition = maxOriginalPosition + 1;

                    for (const s of draft.stages) {
                        const name = s.name.trim();
                        if (s.id !== null) {
                            const orig = originalById.get(s.id);
                            if (orig && (orig.name !== name || orig.success !== s.success || orig.failure !== s.failure)) {
                                await updateStage(s.id, { name, position: orig.position, success: s.success, failure: s.failure });
                            }
                        } else {
                            await createStage(p.id, { name, position: nextNewPosition, success: s.success, failure: s.failure });
                            nextNewPosition++;
                        }
                    }
                }),
            );
            toastSuccess(
                changed.length === 1 ? t('pipelineUpdated') : t('pipelinesUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            setMetricsStatus('idle');
            setStagesByPipeline(new Map());
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOne = useCallback(async (pipeline: Pipeline) => {
        const map = await fetchStagesIfMissing([pipeline.id]);
        setSelectedIds(new Set([pipeline.id]));
        setDrafts({ [pipeline.id]: toDraft(pipeline, map.get(pipeline.id) ?? []) });
        setEditSheetOpen(true);
    }, [fetchStagesIfMissing, setSelectedIds]);

    const deleteOne = useCallback((pipeline: Pipeline) => {
        setSelectedIds(new Set([pipeline.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(Array.from(selectedIds).map((id) => deletePipeline(Number(id))));
            toastSuccess(
                selectedIds.size === 1 ? t('pipelineDeleted') : t('pipelinesDeleted', { count: selectedIds.size }),
            );
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const viewSelected = () => {
        if (selectedPipelines.length === 1) {
            router.push(`/records/pipelines/${selectedPipelines[0].id}`);
        } else {
            selectedPipelines.forEach((p) => window.open(`/records/pipelines/${p.id}`, '_blank'));
        }
    };

    const columns: ColumnDef<Pipeline>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (p) => p.name ?? null, widthClass: 'min-w-48' },
        {
            key: 'createdAt',
            label: t('columnCreated'),
            getSortValue: (p) => (p.createdAt ? Date.parse(p.createdAt) : null),
            render: (p) => p.createdAt,
        },
        {
            key: 'updatedAt',
            label: t('columnUpdated'),
            getSortValue: (p) => (p.updatedAt ? Date.parse(p.updatedAt) : null),
            render: (p) => p.updatedAt,
        },
    ], [t]);

    const metricsByPipelineId = useMemo(() => {
        const map = new Map<number, PipelineMetrics>();
        const WEEK_MS = 7 * 24 * 60 * 60 * 1000;
        const now = Date.now();
        const firstWeekStart = now - 11 * WEEK_MS;

        for (const pipeline of pipelines) {
            const stages = stagesByPipeline.get(pipeline.id) ?? [];
            const deals = allDeals.filter((d) => d.pipeline === pipeline.id);
            const dealsByStage = new Map<number, Deal[]>();
            for (const d of deals) {
                if (d.stage == null) continue;
                const list = dealsByStage.get(d.stage);
                if (list) list.push(d);
                else dealsByStage.set(d.stage, [d]);
            }

            const dealIds = new Set(deals.map((d) => d.id));
            const userIds = new Set<number>();
            for (const t of allTasks) {
                if (t.dealId != null && dealIds.has(t.dealId) && t.assignedToId != null) {
                    userIds.add(t.assignedToId);
                }
            }
            for (const a of allActivities) {
                if (a.dealId != null && dealIds.has(a.dealId)) userIds.add(a.createdById);
            }
            for (const n of allNotes) {
                if (n.deal != null && dealIds.has(n.deal)) userIds.add(n.author);
            }

            const stageMetrics: StageMetrics[] = stages.map((stage) => {
                const stageDeals = dealsByStage.get(stage.id) ?? [];
                const stageDealIds = new Set(stageDeals.map((d) => d.id));
                const tasks = allTasks.filter((t) => t.dealId != null && stageDealIds.has(t.dealId));
                const activities = allActivities.filter((a) => a.dealId != null && stageDealIds.has(a.dealId));
                const notes = allNotes.filter((n) => n.deal != null && stageDealIds.has(n.deal));

                const weeklyEngagement = Array.from({ length: 12 }, (_, i) => ({
                    weekStart: firstWeekStart + i * WEEK_MS,
                    count: 0,
                    activities: 0,
                    tasks: 0,
                    notes: 0,
                }));
                const bucket = (ts: number, kind: 'activities' | 'tasks' | 'notes') => {
                    if (!Number.isFinite(ts)) return;
                    const idx = Math.floor((ts - firstWeekStart) / WEEK_MS);
                    if (idx < 0 || idx >= weeklyEngagement.length) return;
                    weeklyEngagement[idx][kind]++;
                    weeklyEngagement[idx].count++;
                };
                for (const a of activities) bucket(parseMysqlDateTime(a.timestamp), 'activities');
                for (const t of tasks) bucket(parseMysqlDateTime(t.createdAt), 'tasks');
                for (const n of notes) bucket(parseMysqlDateTime(n.createdAt), 'notes');

                return {
                    stage,
                    numDeals: stageDeals.length,
                    numTasks: tasks.length,
                    numActivities: activities.length,
                    numNotes: notes.length,
                    weeklyEngagement,
                };
            });

            map.set(pipeline.id, {
                numStages: stages.length,
                numDeals: deals.length,
                relatedUsers: allUsers.filter((u) => userIds.has(u.id)),
                stages: stageMetrics,
            });
        }
        return map;
    }, [pipelines, stagesByPipeline, allDeals, allTasks, allActivities, allNotes, allUsers]);

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
            <Button variant="outline" size="sm" onClick={openEditSheet}>
                <PencilIcon className="size-4" />
                {t('quickEdit')}
            </Button>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="sm">
                        <EllipsisVerticalIcon className="size-4" />
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent>
                    {selectedPipelines.length === 1 &&
                        (selectedPipelines[0].workspaceId == null || selectedPipelines[0].workspaceId === activeWorkspaceId) && (
                            <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setShareOpen(true); }}>
                                <ShareIcon />
                                {t('share')}
                            </DropdownMenuItem>
                        )}
                    <DropdownMenuItem variant="destructive" onSelect={(e) => { e.preventDefault(); setDeleteDialogOpen(true); }}>
                        <TrashIcon />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </ButtonGroup>
    );

    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <Button className="bg-brand text-white" aria-label={t('addPipelineAriaLabel')} onClick={() => setNewPipelineDialogOpen(true)}>
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <FilterBar
                reduce={reduce}
                chips={query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] as FilterChipData[] : []}
                hasActiveFilters={query.trim() !== ''}
                onClearAll={() => setQuery('')}
                clearAllLabel={tf('clearAll')}
                search={
                    <SearchField
                        value={query}
                        onChange={setQuery}
                        onClear={() => setQuery('')}
                        placeholder={t('searchPlaceholder')}
                        searchAria={tf('searchAria')}
                        clearAria={tf('clearSearchAria')}
                    />
                }
                trailing={
                    <div
                        role="group"
                        aria-label={t('displayModeAriaLabel')}
                        className="inline-flex rounded-full bg-muted p-0.5 ring-1 ring-border"
                    >
                        <button
                            type="button"
                            onClick={() => setDisplayMode('grid')}
                            aria-label={t('gridViewAriaLabel')}
                            aria-pressed={displayMode === 'grid'}
                            className={`flex h-8 w-8 items-center justify-center rounded-full transition ${displayMode === 'grid' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground'}`}
                        >
                            <Squares2X2Icon className="size-4" />
                        </button>
                    </div>
                }
            />

            <RecordsRenderView<Pipeline>
                data={filteredPipelines}
                columns={columns}
                renderCard={(item, { onQuickEdit, onDelete }) => (
                    <PipelineCard
                        pipeline={item}
                        metrics={metricsByPipelineId.get(item.id)}
                        metricsStatus={metricsStatus}
                        onFirstExpand={ensureMetricsLoaded}
                        onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                        onDelete={onDelete ? () => onDelete(item) : undefined}
                    />
                )}
                detailPath={(item) => `/records/pipelines/${item.id}`}
                displayMode={displayMode}
                selectedIds={selectedIds}
                onSelectedIdsChange={setSelectedIds}
                onQuickEdit={quickEditOne}
                onDelete={deleteOne}
                gridClassName="grid grid-cols-1 gap-3"
                entityLabel={t('entityLabel')}
                selectionActions={selectionActions}
            />

            <QuickEditPipelineSheet
                open={editSheetOpen}
                onOpenChange={setEditSheetOpen}
                selectedIds={selectedIds}
                selectedPipelines={selectedPipelines}
                drafts={drafts}
                updateDraft={updateDraft}
                updateStageName={updateStageName}
                updateStageKind={updateStageKind}
                addStage={addStage}
                removeStage={removeStage}
                isSaving={isSaving}
                saveEdits={saveEdits}
            />

            <NewPipelineDialog
                open={newPipelineDialogOpen}
                onOpenChange={closeNewPipelineDialog}
                payload={newPipelinePayload}
                setPayload={setNewPipelinePayload}
                isCreating={isCreating}
                isSuccess={creationSucceeded}
                createNewPipeline={createNewPipeline}
            />

            <DeleteRecordDialog
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedPipelines}
                entityLabel={t('entityLabel')}
                getDisplayName={(p) => p.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />

            {selectedPipelines.length === 1 && (
                <ShareDialog
                    type="pipeline"
                    entityId={selectedPipelines[0].id}
                    entityName={selectedPipelines[0].name}
                    open={shareOpen}
                    onOpenChange={setShareOpen}
                />
            )}
        </div>
    );
}