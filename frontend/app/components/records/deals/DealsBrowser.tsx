'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import RecordsImportExport from '@/app/components/import/RecordsImportExport';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { PlusIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    TableCellsIcon,
    ChevronDownIcon,
    TagIcon,
    UserCircleIcon,
    Bars3BottomLeftIcon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import type { SavedView, SavedViewConfig } from '@/app/lib/types';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { type ColumnDef, applyRecordFilters, deriveFilterOptions, facetChips, countActiveFilters } from '@/app/components/records/types';
import DealCard from '@/app/components/records/deals/DealCard';
import NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import {
    createDeal,
    closeDeal,
    reopenDeal,
    updateDeal,
    getCompanies,
    getPipelines,
    getStagesByPipelineId,
    getDealPeople,
    getTags,
    getActiveWorkspaceMembers,
    bulkAddTagToDeals,
    bulkRemoveTagFromDeals,
    bulkDeleteDeals,
    bulkAssignDealOwner,
    bulkChangeDealStage,
    isFieldError,
} from '@/app/lib/api';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import BulkAssignOwnerDialog from '@/app/components/records/BulkAssignOwnerDialog';
import BulkChangeStageDialog from '@/app/components/records/BulkChangeStageDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { formatCompactCurrency, formatDate, formatDateTime, parseCalendarDate, pickDominantCurrency } from '@/app/lib/utils';
import {
    type Company,
    type CreateDealPayload,
    type Deal,
    type Pipeline,
    type Stage,
    type Contact,
    type UpdateDealPayload,
    type Tag,
    type WorkspaceMember,
} from '@/app/lib/types';
import { isDealClosed } from './dealOutcome';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import ContactAvatar from '../contacts/ContactAvatar';
import SummaryTile from '@/app/components/SummaryTile';
import DealsRevenueChart from '@/app/components/records/deals/DealsRevenueChart';
import StageRatio from '@/app/components/records/deals/StageRatio';

function toDraft(d: Deal): DealDraft {
    return {
        name: d.name ?? '',
        value: d.value ?? 0,
        actualValue: d.actualValue ?? 0,
        currency: d.currency ?? 'USD',
        pipeline: d.pipeline ?? 0,
        stage: d.stage ?? 0,
        company: d.company ?? null,
        expectedCloseDate: d.expectedCloseDate ?? '',
        closedAt: d.closedAt ?? null,
        closedReason: d.closedReason ?? null,
        won: d.won ?? null,
    };
}

function diffDraft(original: DealDraft, draft: DealDraft): boolean {
    return (
        original.name !== draft.name ||
        original.value !== draft.value ||
        original.actualValue !== draft.actualValue ||
        original.currency !== draft.currency ||
        original.pipeline !== draft.pipeline ||
        original.stage !== draft.stage ||
        original.company !== draft.company ||
        original.expectedCloseDate !== draft.expectedCloseDate ||
        original.closedAt !== draft.closedAt ||
        original.closedReason !== draft.closedReason ||
        original.won !== draft.won
    );
}

export default function DealsBrowser({ deals, savedViews }: { deals: Deal[]; savedViews: SavedView[] }) {
    const router = useRouter();
    const t = useTranslations('DealsBrowser');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;

    const [companies, setCompanies] = useState<Company[]>([]);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [contactByDealId, setContactByDealId] = useState<Map<number, Contact>>(new Map());
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});

    useEffect(() => {
        getCompanies({}).then(setCompanies).catch(() => setCompanies([]));
        getPipelines().then(async (ps) => {
            setPipelines(ps);
            const entries = await Promise.all(
                ps.map(async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const),
            );
            setStagesByPipeline(Object.fromEntries(entries));
        }).catch(() => setPipelines([]));
    }, []);

    useEffect(() => {
        const freelancerDeals = deals.filter((d) => d.company == null);
        const request = freelancerDeals.length === 0
            ? Promise.resolve([] as readonly (readonly [number, Contact | undefined])[])
            : Promise.all(
                freelancerDeals.map(async (d) => {
                    const people = await getDealPeople(d.id).catch(() => [] as Contact[]);
                    return [d.id, people[0]] as const;
                }),
            );
        request.then((entries) => {
            const m = new Map<number, Contact>();
            for (const [id, contact] of entries) {
                if (contact) m.set(id, contact);
            }
            setContactByDealId(m);
        });
    }, [deals]);

    const companyById = useMemo(() => new Map(companies.map((c) => [c.id, c])), [companies]);
    const pipelineById = useMemo(() => new Map(pipelines.map((p) => [p.id, p])), [pipelines]);
    const stageById = useMemo(() => {
        const m = new Map<number, Stage>();
        for (const stages of Object.values(stagesByPipeline)) {
            for (const s of stages) m.set(s.id, s);
        }
        return m;
    }, [stagesByPipeline]);

    const currencyCounts = useMemo(() => {
        const counts = new Map<string, number>();
        for (const d of deals) {
            const c = d.currency || 'USD';
            counts.set(c, (counts.get(c) ?? 0) + 1);
        }
        return counts;
    }, [deals]);
    const dominantCurrency = useMemo(() => pickDominantCurrency(deals), [deals]);
    const [selectedCurrency, setSelectedCurrency] = useState<string | null>(null);
    const activeCurrency = selectedCurrency && currencyCounts.has(selectedCurrency)
        ? selectedCurrency
        : dominantCurrency;
    const dealsInCurrency = useMemo(
        () => deals.filter((d) => (d.currency || 'USD') === activeCurrency),
        [deals, activeCurrency],
    );
    
    const searchFields = useCallback((d: Deal) => [
        d.name,
        d.currency,
        d.company != null ? companyById.get(d.company)?.name : undefined,
        d.pipeline != null ? pipelineById.get(d.pipeline)?.name : undefined,
        d.stage != null ? stageById.get(d.stage)?.name : undefined,
    ], [companyById, pipelineById, stageById]);

    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredDeals,
        selectedItems: selectedDeals,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Deal>({
        items: dealsInCurrency,
        storageKey: 'deals:view',
        searchFields,
    });

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, DealDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const emptyDraft: CreateDealPayload = {
        name: '',
        value: 0,
        actualValue: 0,
        currency: 'USD',
        pipeline: 0,
        stage: 0,
        company: null,
        expectedCloseDate: undefined,
    };
    const [newDialogOpen, setNewDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newPayload, setNewPayload] = useState<CreateDealPayload>(emptyDraft);

    const closeNewDialog = (open: boolean) => {
        setNewDialogOpen(open);
        if (!open) {
            setNewPayload(emptyDraft);
            setCreationSucceeded(false);
        }
    };

    const createNewDeal = async () => {
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            await createDeal({
                ...newPayload,
                name: newPayload.name.trim(),
                value: Number.isFinite(newPayload.value) ? newPayload.value : 0,
                actualValue: Number.isFinite(newPayload.actualValue) ? newPayload.actualValue : 0,
                currency: newPayload.currency.trim() || 'USD',
                pipeline: newPayload.pipeline || null,
                stage: newPayload.stage || null,
                expectedCloseDate: newPayload.expectedCloseDate || undefined,
            });
            toastSuccess(t('dealCreated'));
            setIsCreating(false);
            setCreationSucceeded(true);
            setTimeout(() => {
                closeNewDialog(false);
                router.refresh();
            }, 900);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            console.error(err);
            toastError(err instanceof Error ? err.message : t('failedToCreateDeal'));
        } finally {
            setIsCreating(false);
        }
    };

    const openEditSheet = () => {
        const next: Record<number, DealDraft> = {};
        for (const d of selectedDeals) next[d.id] = toDraft(d);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<DealDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedDeals.filter((d) => {
            const draft = drafts[d.id];
            return draft && diffDraft(toDraft(d), draft);
        });

        if (changed.length === 0) {
            toast.info(t('noChangesToSave'));
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((d) => {
            const draft = drafts[d.id];
            return !draft.name.trim() || !draft.pipeline || !draft.stage || !draft.currency.trim();
        });
        if (invalid) {
            toast.error(t('validationRequired', { name: invalid.name }));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((d) => {
                    const draft = drafts[d.id];
                    const payload: UpdateDealPayload = {
                        name: draft.name.trim(),
                        value: draft.value,
                        actualValue: draft.actualValue,
                        currency: draft.currency.trim(),
                        pipeline: draft.pipeline,
                        stage: draft.stage,
                        company: draft.company ?? null,
                        expectedCloseDate: draft.expectedCloseDate || undefined,
                        closedAt: draft.closedAt,
                        closedReason: draft.closedReason,
                        won: draft.won,
                    };
                    return updateDeal(d.id, payload);
                }),
            );
            toastSuccess(
                changed.length === 1 ? t('dealUpdated') : t('dealsUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOne = useCallback((deal: Deal) => {
        setSelectedIds(new Set([deal.id]));
        setDrafts({ [deal.id]: toDraft(deal) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((deal: Deal) => {
        setSelectedIds(new Set([deal.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const selectedDealIds = useMemo(() => selectedDeals.map((d) => d.id), [selectedDeals]);

    const confirmDelete = async () => {
        if (selectedDealIds.length === 0) return;
        setIsDeleting(true);
        try {
            const result = await bulkDeleteDeals(selectedDealIds);
            const anySucceeded = notifyBulkResult(result, {
                success: (count) => count === 1 ? t('dealDeleted') : t('dealsDeleted', { count }),
                partial: (succeeded, total) => t('dealsDeletedPartial', { succeeded, total }),
                failure: (failed) => t('dealsDeleteFailed', { failed }),
            });
            setDeleteDialogOpen(false);
            if (anySucceeded) {
                setSelectedIds(new Set());
                router.refresh();
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const [tags, setTags] = useState<Tag[]>([]);
    useEffect(() => { getTags().then(setTags).catch(() => setTags([])); }, []);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    useEffect(() => { getActiveWorkspaceMembers().then(setMembers).catch(() => setMembers([])); }, []);

    const [bulkTag, setBulkTag] = useState<{ open: boolean; mode: 'add' | 'remove' }>({ open: false, mode: 'add' });
    const [bulkOwnerOpen, setBulkOwnerOpen] = useState(false);
    const [bulkStageOpen, setBulkStageOpen] = useState(false);

    const { stageOptions, mixedPipelines } = useMemo(() => {
        const distinctPipelines = new Set(selectedDeals.map((d) => d.pipeline ?? null));
        if (distinctPipelines.size > 1) return { stageOptions: [] as Stage[], mixedPipelines: true };
        const commonPipelineId = [...distinctPipelines][0];
        const options = commonPipelineId != null ? (stagesByPipeline[commonPipelineId] ?? []) : [];
        return { stageOptions: options, mixedPipelines: false };
    }, [selectedDeals, stagesByPipeline]);

    const applyBulkTag = useCallback((tagId: number) => {
        return bulkTag.mode === 'add'
            ? bulkAddTagToDeals(selectedDealIds, tagId)
            : bulkRemoveTagFromDeals(selectedDealIds, tagId);
    }, [bulkTag.mode, selectedDealIds]);
    const onBulkSuccess = useCallback(() => { setSelectedIds(new Set()); router.refresh(); }, [setSelectedIds, router]);

    const viewSelected = () => {
        if (selectedDeals.length === 1) {
            router.push(`/records/deals/${selectedDeals[0].id}`);
        } else {
            selectedDeals.forEach((d) => window.open(`/records/deals/${d.id}`, '_blank'));
        }
    };

    const toggleDealStatus = useCallback(async (deal: Deal, won: boolean | null) => {
        try {
            if (won === null) await reopenDeal(deal.id);
            else await closeDeal(deal.id, { won });
            toastSuccess(won === null ? t('dealReopened') : t('dealClosed'));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToUpdateStatus'));
        }
    }, [router, t]);

    const summary = useMemo(() => {
        let openCount = 0;
        let openValue = 0;
        let closedActualValue = 0;
        let closedForecastValue = 0;
        for (const d of dealsInCurrency) {
            if (isDealClosed(d)) {
                closedActualValue += d.actualValue ?? 0;
                closedForecastValue += d.value ?? 0;
            } else {
                openCount++;
                openValue += d.value ?? 0;
            }
        }
        const forecastAccuracy = closedForecastValue > 0 ? closedActualValue / closedForecastValue : null;
        return { openCount, openValue, closedActualValue, closedForecastValue, forecastAccuracy };
    }, [dealsInCurrency]);

    const columns: ColumnDef<Deal>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (d) => d.name ?? null, widthClass: 'min-w-48' },
        {
            key: 'value',
            label: t('columnValue'),
            getSortValue: (d) => d.value ?? null,
            render: (d) => formatCompactCurrency(d.value ?? 0, d.currency || 'USD', locale),
        },
        {
            key: 'actualValue',
            label: t('columnActualValue'),
            getSortValue: (d) => d.actualValue ?? null,
            render: (d) => formatCompactCurrency(d.actualValue ?? 0, d.currency || 'USD', locale),
        },
        {
            key: 'company',
            label: t('columnCompany'),
            getSortValue: (d) => (d.company != null ? companyById.get(d.company)?.name ?? null : null),
            render: (d) => (d.company != null ? <Link href={`/records/companies/${d.company}`} className="text-brand hover:text-brand-dark hover:underline transition-colors transition-duration-300 transition-ease-in-out">{companyById.get(d.company)?.name}</Link> : ''),
            filter: { getValue: (d) => (d.company != null ? companyById.get(d.company)?.name ?? null : null), emptyLabel: t('freelancer') },
        },
        {
            key: 'pipeline',
            label: t('columnPipeline'),
            getSortValue: (d) => (d.pipeline != null ? pipelineById.get(d.pipeline)?.name ?? null : null),
            render: (d) => (d.pipeline != null ? pipelineById.get(d.pipeline)?.name : ''),
            filter: { getValue: (d) => (d.pipeline != null ? pipelineById.get(d.pipeline)?.name ?? null : null) },
        },
        {
            key: 'stage',
            label: t('columnStage'),
            getSortValue: (d) => (d.stage != null ? stageById.get(d.stage)?.name ?? null : null),
            render: (d) => (d.stage != null ? stageById.get(d.stage)?.name : ''),
            filter: { getValue: (d) => (d.stage != null ? stageById.get(d.stage)?.name ?? null : null) },
        },
        {
            key: 'expectedCloseDate',
            label: t('columnExpectedClose'),
            getSortValue: (d) => (d.expectedCloseDate ? parseCalendarDate(d.expectedCloseDate) : null),
            render: (d) => formatDate(d.expectedCloseDate, locale),
        },
        {
            key: 'status',
            label: t('columnStatus'),
            getSortValue: (d) => (isDealClosed(d) ? 1 : 0),
            filter: {
                getValue: (d) => (isDealClosed(d) ? 'closed' : 'open'),
                formatValue: (v) => (v === 'closed' ? t('statusClosed') : t('statusOpen')),
            },
            render: (d) => {
                const closed = isDealClosed(d);
                return (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs ring-1 ring-border transition hover:bg-muted/80"
                            >
                                <span className={closed ? 'text-gray-500' : 'text-emerald-300'}>●</span>
                                {closed ? t('statusClosed') : t('statusOpen')}
                                <ChevronDownIcon className="size-3 text-muted-foreground" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" onClick={(e) => e.stopPropagation()}>
                            {closed ? (
                                <DropdownMenuItem onSelect={() => toggleDealStatus(d, null)}>
                                    <span className="text-emerald-300">●</span>
                                    {t('markOpen')}
                                </DropdownMenuItem>
                            ) : (
                                <>
                                    <DropdownMenuItem onSelect={() => toggleDealStatus(d, true)}>
                                        <span className="text-emerald-400">●</span>
                                        {t('markWon')}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onSelect={() => toggleDealStatus(d, false)}>
                                        <span className="text-red-400">●</span>
                                        {t('markLost')}
                                    </DropdownMenuItem>
                                </>
                            )}
                        </DropdownMenuContent>
                    </DropdownMenu>
                );
            },
        },
        {
            key: 'updatedAt',
            label: t('columnUpdated'),
            getSortValue: (d) => (d.updatedAt ? Date.parse(d.updatedAt) : null),
            render: (d) => formatDateTime(d.updatedAt, locale),
        },
    ], [companyById, pipelineById, stageById, toggleDealStatus, t, locale]);

    const visibleDeals = useMemo(
        () => applyRecordFilters(filteredDeals, columns, filterState),
        [filteredDeals, columns, filterState],
    );

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('deal', visibleDeals);

    const facets = useMemo(() => deriveFilterOptions(columns, filteredDeals), [columns, filteredDeals]);
    const hasActiveFilters = query.trim() !== '' || countActiveFilters(filterState) > 0;
    const clearAll = useCallback(() => { setQuery(''); setFilterState({}); }, [setQuery, setFilterState]);
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...facetChips(facets, filterState, setFilterState),
    ];

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
            <Button variant="outline" size="sm" onClick={viewSelected}>
                <EyeIcon className="size-4" />
                {t('view')}
            </Button>
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
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkStageOpen(true); }}>
                        <Bars3BottomLeftIcon />
                        {t('changeStage')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkOwnerOpen(true); }}>
                        <UserCircleIcon />
                        {t('assignOwner')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'add' }); }}>
                        <TagIcon />
                        {t('addTag')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'remove' }); }}>
                        <TagIcon />
                        {t('removeTag')}
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive" onSelect={(e) => { e.preventDefault(); setDeleteDialogOpen(true); }}>
                        <TrashIcon />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </ButtonGroup>
    );

    const currentConfig: SavedViewConfig = useMemo(
        () => ({ filters: filterState, query }),
        [filterState, query],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            setFilterState(config.filters ?? {});
            setQuery(config.query ?? '');
        },
        [setFilterState, setQuery],
    );

    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <div className="flex items-center gap-2">
                    {currencyCounts.size > 1 && (
                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <button
                                    type="button"
                                    aria-label={t('currency')}
                                    className="flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-sm text-foreground ring-1 ring-border transition hover:bg-muted/80"
                                >
                                    {activeCurrency}
                                    <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                                </button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                {Array.from(currencyCounts.entries())
                                    .sort((a, b) => b[1] - a[1])
                                    .map(([c, n]) => (
                                        <DropdownMenuItem key={c} onSelect={() => setSelectedCurrency(c)}>
                                            <span className={c === activeCurrency ? 'font-semibold' : ''}>{c}</span>
                                            <span className="ml-auto text-xs text-muted-foreground">{t('currencyCount', { count: n })}</span>
                                        </DropdownMenuItem>
                                    ))}
                            </DropdownMenuContent>
                        </DropdownMenu>
                    )}
                    <RecordsImportExport entity="deals" onImported={() => router.refresh()} exportIds={visibleDeals.map((d) => d.id)} />
                    <Button className="bg-brand text-white" aria-label={t('addDeal')} onClick={() => setNewDialogOpen(true)}>
                        <PlusIcon strokeWidth={2.5} />
                        {t('newButton')}
                    </Button>
                </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <SummaryTile className="sm:col-span-2" label={t('revenueTrend')} value={<DealsRevenueChart deals={dealsInCurrency} />} />
                <SummaryTile label={t('stageRatio')} value={<StageRatio deals={dealsInCurrency} />} />
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
                <SummaryTile
                    label={t('openPipeline')}
                    tooltip={t('openPipelineTooltip')}
                    value={formatCompactCurrency(summary.openValue, activeCurrency, locale)}
                />
                <SummaryTile
                    label={t('openDeals')}
                    tooltip={t('openDealsTooltip')}
                    value={String(summary.openCount)}
                />
                <SummaryTile
                    label={t('closedForecast')}
                    tooltip={t('closedForecastTooltip')}
                    value={formatCompactCurrency(summary.closedForecastValue, activeCurrency, locale)}
                />
                <SummaryTile
                    label={t('closedRevenue')}
                    tooltip={t('closedRevenueTooltip')}
                    value={formatCompactCurrency(summary.closedActualValue, activeCurrency, locale)}
                />
                <SummaryTile
                    label={t('forecastAccuracy')}
                    tooltip={t('forecastAccuracyTooltip')}
                    value={summary.forecastAccuracy != null ? `${Math.round(summary.forecastAccuracy * 100)}%` : '—'}
                />
            </div>

            <SavedViewsBar
                recordType="deal"
                initialViews={savedViews}
                currentConfig={currentConfig}
                onApply={applyView}
            />

            <FilterBar
                reduce={reduce}
                chips={chips}
                hasActiveFilters={hasActiveFilters}
                onClearAll={clearAll}
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
                        aria-label={t('displayMode')}
                        className="inline-flex rounded-full bg-muted p-0.5 ring-1 ring-border"
                    >
                        <button
                            type="button"
                            onClick={() => setDisplayMode('table')}
                            aria-label={t('tableView')}
                            aria-pressed={displayMode === 'table'}
                            className={`flex h-8 w-8 items-center justify-center rounded-full transition ${displayMode === 'table' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground'}`}
                        >
                            <TableCellsIcon className="size-4" />
                        </button>
                    </div>
                }
            >
                <RecordsFilterPills<Deal>
                    facets={facets}
                    filterState={filterState}
                    onChange={setFilterState}
                />
            </FilterBar>

            <RecordsRenderView<Deal>
                data={visibleDeals}
                columns={[...columns, ...customColumns]}
                addColumnSlot={addColumnSlot}
                renderCard={(item, { onQuickEdit, onDelete }) => (
                    <DealCard
                        deal={item}
                        company={item.company != null ? companyById.get(item.company) : undefined}
                        pipeline={item.pipeline != null ? pipelineById.get(item.pipeline) : undefined}
                        stage={item.stage != null ? stageById.get(item.stage) : undefined}
                        onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                        onDelete={onDelete ? () => onDelete(item) : undefined}
                    />
                )}
                renderAvatar={(item) => {
                    const company = item.company != null ? companyById.get(item.company) : undefined;
                    if (company) return <CompanyAvatar company={company} type="large" />;
                    const contact = contactByDealId.get(item.id);
                    return (
                        <ContactAvatar
                            contact={contact ?? { id: 0, name: t('freelancer'), imageUrl: '', email: '', phone: '', title: '', createdAt: '', updatedAt: '' }}
                            type="large"
                        />
                    );
                }}
                detailPath={(item) => `/records/deals/${item.id}`}
                displayMode={displayMode}
                selectedIds={selectedIds}
                onSelectedIdsChange={setSelectedIds}
                onQuickEdit={quickEditOne}
                onDelete={deleteOne}
                gridClassName="grid grid-cols-1 gap-3"
                entityLabel={t('entityLabel')}
                selectionActions={selectionActions}
            />

            <QuickEditDealSheet
                open={editSheetOpen}
                onOpenChange={setEditSheetOpen}
                selectedIds={selectedIds}
                selectedDeals={selectedDeals}
                drafts={drafts}
                updateDraft={updateDraft}
                companies={companies}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isSaving={isSaving}
                saveEdits={saveEdits}
            />

            <NewDealDialog
                open={newDialogOpen}
                onOpenChange={closeNewDialog}
                payload={newPayload}
                setPayload={setNewPayload}
                companies={companies}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isCreating={isCreating}
                isSuccess={creationSucceeded}
                createNewDeal={createNewDeal}
            />

            <DeleteRecordDialog
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedDeals}
                entityLabel={t('entityLabel')}
                getDisplayName={(d) => d.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />

            <BulkTagDialog
                open={bulkTag.open}
                onOpenChange={(open) => setBulkTag((s) => ({ ...s, open }))}
                mode={bulkTag.mode}
                count={selectedDealIds.length}
                tags={tags}
                messages={{
                    success: (count) => t(bulkTag.mode === 'add' ? 'toastTagAdded' : 'toastTagRemoved', { count }),
                    partial: (succeeded, total) => t(bulkTag.mode === 'add' ? 'toastTagAddedPartial' : 'toastTagRemovedPartial', { succeeded, total }),
                    failure: (failed) => t('toastTagFailed', { failed }),
                }}
                onApply={applyBulkTag}
                onSuccess={onBulkSuccess}
            />

            <BulkAssignOwnerDialog
                open={bulkOwnerOpen}
                onOpenChange={setBulkOwnerOpen}
                count={selectedDealIds.length}
                members={members}
                messages={{
                    success: (count) => t('toastOwnerAssigned', { count }),
                    partial: (succeeded, total) => t('toastOwnerAssignedPartial', { succeeded, total }),
                    failure: (failed) => t('toastOwnerFailed', { failed }),
                }}
                onApply={(ownerId) => bulkAssignDealOwner(selectedDealIds, ownerId)}
                onSuccess={onBulkSuccess}
            />

            <BulkChangeStageDialog
                open={bulkStageOpen}
                onOpenChange={setBulkStageOpen}
                count={selectedDealIds.length}
                stages={stageOptions}
                mixedPipelines={mixedPipelines}
                messages={{
                    success: (count) => t('toastStageChanged', { count }),
                    partial: (succeeded, total) => t('toastStageChangedPartial', { succeeded, total }),
                    failure: (failed) => t('toastStageFailed', { failed }),
                }}
                onApply={(stageId) => bulkChangeDealStage(selectedDealIds, stageId)}
                onSuccess={onBulkSuccess}
            />
        </div>
    );
}