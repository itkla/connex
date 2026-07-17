'use client';

import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import RecordsActions from '@/app/components/import/RecordsActions';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    TableCellsIcon,
    Squares2X2Icon,
    ViewColumnsIcon,
    ChevronDownIcon,
    TagIcon,
    UserCircleIcon,
    Bars3BottomLeftIcon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import type { SavedView, SavedViewConfig } from '@/app/lib/types';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, MemberScopeFilter, interpretMemberScope, MEMBER_SCOPE_ME, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { FILTER_EMPTY, type ColumnDef, type ColumnFilterFacet, type FilterState, facetChips, countActiveFilters } from '@/app/components/records/types';
import DealCard from '@/app/components/records/deals/DealCard';
import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import { useRiskText } from '@/app/components/records/deals/dealRisk';
import DealsKanban from '@/app/components/records/deals/DealsKanban';
import NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import {
    createDeal,
    closeDeal,
    reopenDeal,
    updateDeal,
    getCompaniesByIds,
    getDealsPage,
    getDealMetrics,
    getDealFacets,
    exportDealsCsv,
    getDealRevenueTimeseries,
    getDealStageDistribution,
    getPipelines,
    getStagesByPipelineId,
    getDealPrimaryContacts,
    getDealRisks,
    getTags,
    getActiveWorkspaceMembers,
    bulkAddTagToDeals,
    bulkRemoveTagFromDeals,
    bulkDeleteDeals,
    bulkAssignDealOwner,
    bulkChangeDealStage,
    ApiError,
    isFieldError,
} from '@/app/lib/api';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import BulkAssignOwnerDialog from '@/app/components/records/BulkAssignOwnerDialog';
import BulkChangeStageDialog from '@/app/components/records/BulkChangeStageDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { formatCompactCurrency, formatDate, formatDateTime, parseCalendarDate } from '@/app/lib/utils';
import {
    type Company,
    type CreateDealPayload,
    type Deal,
    type DealRisk,
    type DealMetrics,
    type DealFacets,
    type DealFilterParams,
    type DealRevenueSeries,
    type DealStageDistribution,
    type DealsPageParams,
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

/**
 * Maps a table column key to the backend {@code sort} token accepted by
 * {@code GET /api/deals/page}. Columns absent here are not server-sortable.
 */
const DEAL_SORT_TOKENS: Record<string, string> = {
    name: 'name',
    value: 'value',
    actualValue: 'actual_value',
    company: 'company',
    pipeline: 'pipeline',
    stage: 'stage',
    expectedCloseDate: 'expected_close_date',
    status: 'status',
    updatedAt: 'updated_at',
};

const EMPTY_DEAL_METRICS: DealMetrics = { byCurrency: [], totalCount: 0 };
const EMPTY_DEAL_DRAFT: CreateDealPayload = {
    name: '',
    value: 0,
    actualValue: 0,
    currency: 'USD',
    pipeline: 0,
    stage: 0,
    company: null,
    expectedCloseDate: undefined,
};

const DEAL_FILTER_KEYS = ['status', 'company', 'pipeline', 'stage', 'risk', 'owner'] as const;

function resolveNamedFacetIds<T extends { id: number; name: string }>(values: string[] | undefined, items: T[]): number[] | undefined {
    if (!values?.length) return undefined;
    const ids = values.flatMap((value) => {
        if (value === FILTER_EMPTY) return [];
        const id = Number(value);
        if (Number.isInteger(id) && id > 0) return [id];
        return items.flatMap((item) => item.name === value ? [item.id] : []);
    });
    if (ids.length > 0) return Array.from(new Set(ids));
    return values.some((value) => value !== FILTER_EMPTY) ? [0] : undefined;
}

function resolveCompanyFacetIds(values: string[] | undefined, facets: DealFacets['companies']): number[] | undefined {
    if (!values?.length) return undefined;
    const ids = values.flatMap((value) => {
        if (value === FILTER_EMPTY) return [];
        const id = Number(value);
        if (Number.isInteger(id) && id > 0) return [id];
        return facets.flatMap((facet) => {
            if (facet.label !== value) return [];
            const facetId = Number(facet.key);
            return Number.isInteger(facetId) && facetId > 0 ? [facetId] : [];
        });
    });
    if (ids.length > 0) return Array.from(new Set(ids));
    return values.some((value) => value !== FILTER_EMPTY) ? [0] : undefined;
}

function normalizeDealFilters(filters: FilterState): FilterState {
    const normalized: FilterState = {};
    for (const key of DEAL_FILTER_KEYS) {
        const values = filters[key]?.filter(Boolean);
        if (values?.length) normalized[key] = Array.from(new Set(values));
    }
    return normalized;
}

export default function DealsBrowser({ deals: initialDeals, total: initialTotal, metrics: initialMetrics, serverFacets: initialFacets, savedViews, timezone, currentUserId }: { deals: Deal[]; total: number; metrics: DealMetrics; serverFacets: DealFacets; savedViews: SavedView[]; timezone: string; currentUserId: number }) {
    const router = useRouter();
    const t = useTranslations('DealsBrowser');
    const tf = useTranslations('Filters');
    const ts = useTranslations('MemberScope');
    const { levelLabel } = useRiskText();
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const [deals, setDeals] = useState(initialDeals);
    const [total, setTotal] = useState(initialTotal);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [loadingPage, setLoadingPage] = useState(false);
    const [sortKey, setSortKey] = useState<string | null>(null);
    const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
    const [dealMetrics, setDealMetrics] = useState(initialMetrics);
    const [dealFacets, setDealFacets] = useState(initialFacets);
    const [dataRevision, setDataRevision] = useState(0);
    const [pageRequestError, setPageRequestError] = useState<string | null>(null);
    const [metricsRequestError, setMetricsRequestError] = useState<string | null>(null);
    const requestError = pageRequestError ?? metricsRequestError;
    const loadErrorMessage = useCallback((error: unknown, riskActive: boolean) =>
        error instanceof ApiError && error.status === 400 && riskActive
            ? t('riskFilterTooBroad')
            : t('loadFailed'), [t]);
    const requestIdRef = useRef(0);
    const loadDealsPage = useCallback(async (params: DealsPageParams) => {
        const requestId = requestIdRef.current + 1;
        requestIdRef.current = requestId;
        setLoadingPage(true);
        try {
            const response = await getDealsPage(params);
            if (requestId !== requestIdRef.current) return;
            setDeals(response.items);
            setTotal(response.total);
            setPageRequestError(null);
            const maxPage = Math.max(1, Math.ceil(response.total / (params.size ?? 25)));
            if ((params.page ?? 1) > maxPage) setPage(maxPage);
        } catch (error: unknown) {
            if (requestId !== requestIdRef.current) return;
            const message = loadErrorMessage(error, (params.risk?.length ?? 0) > 0);
            setPageRequestError(message);
            toastError(message);
        } finally {
            if (requestId === requestIdRef.current) setLoadingPage(false);
        }
    }, [loadErrorMessage]);
    const refreshData = useCallback(() => setDataRevision((revision) => revision + 1), []);

    const [companies, setCompanies] = useState<Company[]>([]);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [contactByDealId, setContactByDealId] = useState<Map<number, Contact>>(new Map());
    const [riskByDealId, setRiskByDealId] = useState<Map<number, DealRisk>>(new Map());
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});

    useEffect(() => {
        getPipelines().then(async (ps) => {
            setPipelines(ps);
            const entries = await Promise.all(
                ps.map(async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const),
            );
            setStagesByPipeline(Object.fromEntries(entries));
        }).catch(() => setPipelines([]));
    }, []);

    useEffect(() => {
        let cancelled = false;
        getCompaniesByIds(deals.flatMap((deal) => deal.company == null ? [] : [deal.company]))
            .then((loaded) => {
                if (!cancelled) setCompanies(loaded);
            })
            .catch(() => {
                if (!cancelled) setCompanies([]);
            });
        return () => {
            cancelled = true;
        };
    }, [deals]);

    useEffect(() => {
        let cancelled = false;
        const freelancerDealIds = deals.flatMap((deal) => deal.company == null ? [deal.id] : []);
        getDealPrimaryContacts(freelancerDealIds).then((entries) => {
            if (cancelled) return;
            const m = new Map<number, Contact>();
            for (const entry of entries) {
                m.set(entry.dealId, {
                    id: entry.personId,
                    name: entry.name,
                    imageUrl: entry.imageUrl,
                    email: '',
                    phone: '',
                    title: '',
                    createdAt: '',
                    updatedAt: '',
                });
            }
            setContactByDealId(m);
        }).catch(() => {
            if (!cancelled) setContactByDealId(new Map());
        });
        return () => {
            cancelled = true;
        };
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
        for (const c of dealMetrics.byCurrency) counts.set(c.currency, c.openCount + c.closedCount);
        return counts;
    }, [dealMetrics]);
    const dominantCurrency = useMemo(() => {
        let best: string | null = null;
        let bestCount = -1;
        for (const c of dealMetrics.byCurrency) {
            const n = c.openCount + c.closedCount;
            if (n > bestCount) {
                bestCount = n;
                best = c.currency;
            }
        }
        return best;
    }, [dealMetrics]);
    const [selectedCurrency, setSelectedCurrency] = useState<string | null>(null);
    const activeCurrency = selectedCurrency && currencyCounts.has(selectedCurrency)
        ? selectedCurrency
        : dominantCurrency;
    const displayCurrency = activeCurrency ?? 'USD';
    const [revenueSeries, setRevenueSeries] = useState<DealRevenueSeries>({ closed: [], projected: [] });
    const [stageDistribution, setStageDistribution] = useState<DealStageDistribution[]>([]);
    useEffect(() => {
        let cancelled = false;
        if (!activeCurrency) {
            return () => {
                cancelled = true;
            };
        }
        getDealRevenueTimeseries(activeCurrency, timezone)
            .then((series) => { if (!cancelled) setRevenueSeries(series); })
            .catch(() => { if (!cancelled) setRevenueSeries({ closed: [], projected: [] }); });
        getDealStageDistribution(activeCurrency)
            .then((distribution) => { if (!cancelled) setStageDistribution(distribution); })
            .catch(() => { if (!cancelled) setStageDistribution([]); });
        return () => { cancelled = true; };
    }, [activeCurrency, dataRevision, timezone]);

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
        selectedItems: pageSelectedDeals,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Deal>({
        items: deals,
        storageKey: 'deals:view',
        searchFields,
    });
    const [selectedBoardDeal, setSelectedBoardDeal] = useState<Deal | null>(null);
    const selectedDeals = useMemo(() => {
        if (pageSelectedDeals.length > 0) return pageSelectedDeals;
        return selectedBoardDeal != null && selectedIds.has(selectedBoardDeal.id)
            ? [selectedBoardDeal]
            : [];
    }, [pageSelectedDeals, selectedBoardDeal, selectedIds]);

    const allStages = useMemo(() => Object.values(stagesByPipeline).flat(), [stagesByPipeline]);
    const activeFilterState = useMemo(() => normalizeDealFilters(filterState), [filterState]);
    const ownerScope = useMemo(() => interpretMemberScope(activeFilterState.owner), [activeFilterState.owner]);
    const serverFilters = useMemo<DealFilterParams>(() => {
        const status = activeFilterState.status?.filter(
            (value): value is 'open' | 'closed' | 'won' | 'lost' =>
                value === 'open' || value === 'closed' || value === 'won' || value === 'lost',
        );
        const risk = activeFilterState.risk?.filter(
            (value): value is 'high' | 'medium' | 'low' | 'none' =>
                value === 'high' || value === 'medium' || value === 'low' || value === 'none',
        );
        return {
            status: status?.length ? status : undefined,
            risk: risk?.length ? risk : undefined,
            companyId: resolveCompanyFacetIds(activeFilterState.company, dealFacets.companies),
            noCompany: activeFilterState.company?.includes(FILTER_EMPTY) || undefined,
            pipelineId: resolveNamedFacetIds(activeFilterState.pipeline, pipelines),
            stageId: resolveNamedFacetIds(activeFilterState.stage, allStages),
            scope: ownerScope.mode === 'all' ? undefined : ownerScope.mode,
            memberIds: ownerScope.mode === 'members' ? ownerScope.memberIds : undefined,
        };
    }, [activeFilterState, dealFacets.companies, pipelines, allStages, ownerScope]);
    const serverFilterKey = useMemo(() => JSON.stringify(serverFilters), [serverFilters]);
    const deferredQuery = useDeferredValue(query.trim());

    useEffect(() => {
        let active = true;
        getDealMetrics({ ...serverFilters, q: deferredQuery || undefined })
            .then((nextMetrics) => {
                if (!active) return;
                setDealMetrics(nextMetrics);
                setMetricsRequestError(null);
            })
            .catch((error: unknown) => {
                if (!active) return;
                const message = loadErrorMessage(error, (serverFilters.risk?.length ?? 0) > 0);
                setMetricsRequestError(message);
            });
        return () => { active = false; };
    }, [serverFilterKey, deferredQuery, dataRevision, serverFilters, loadErrorMessage]);

    useEffect(() => {
        let active = true;
        getDealFacets()
            .then((nextFacets) => { if (active) setDealFacets(nextFacets); })
            .catch(() => undefined);
        return () => { active = false; };
    }, [dataRevision]);

    useEffect(() => {
        const timer = window.setTimeout(() => {
            loadDealsPage({
                page,
                size,
                q: deferredQuery || undefined,
                ...serverFilters,
                currency: activeCurrency ?? undefined,
                sort: sortKey ? DEAL_SORT_TOKENS[sortKey] : undefined,
                dir: sortKey ? sortDir : undefined,
            });
        }, 0);
        return () => window.clearTimeout(timer);
    }, [page, size, deferredQuery, serverFilterKey, activeCurrency, sortKey, sortDir, dataRevision, serverFilters, loadDealsPage]);

    const changePage = useCallback((nextPage: number) => {
        setSelectedIds(new Set());
        setPage(nextPage);
    }, [setSelectedIds]);
    const changePageSize = useCallback((nextSize: number) => {
        setSelectedIds(new Set());
        setSize(nextSize);
        setPage(1);
    }, [setSelectedIds]);
    const changeQuery = useCallback((nextQuery: string) => {
        setSelectedIds(new Set());
        setQuery(nextQuery);
        setPage(1);
    }, [setQuery, setSelectedIds]);
    const changeFilters = useCallback((nextFilters: FilterState) => {
        setSelectedIds(new Set());
        setFilterState(normalizeDealFilters(nextFilters));
        setPage(1);
    }, [setFilterState, setSelectedIds]);
    const handleSortChange = useCallback((columnKey: string) => {
        if (!DEAL_SORT_TOKENS[columnKey]) return;
        const nextDir: 'asc' | 'desc' = sortKey === columnKey && sortDir === 'asc' ? 'desc' : 'asc';
        setSelectedIds(new Set());
        setSortKey(columnKey);
        setSortDir(nextDir);
        setPage(1);
    }, [sortKey, sortDir, setSelectedIds]);
    const changeCurrency = useCallback((currency: string) => {
        setSelectedIds(new Set());
        setRevenueSeries({ closed: [], projected: [] });
        setStageDistribution([]);
        setSelectedCurrency(currency);
        setPage(1);
    }, [setSelectedIds]);
    const refreshRecords = useCallback(() => {
        setSelectedIds(new Set());
        setDealMetrics(EMPTY_DEAL_METRICS);
        setRevenueSeries({ closed: [], projected: [] });
        setStageDistribution([]);
        refreshData();
    }, [setSelectedIds, refreshData]);

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, DealDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const [newDialogOpen, setNewDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newPayload, setNewPayload] = useState<CreateDealPayload>(EMPTY_DEAL_DRAFT);

    const closeNewDialog = (open: boolean) => {
        setNewDialogOpen(open);
        if (!open) {
            setNewPayload(EMPTY_DEAL_DRAFT);
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
                refreshRecords();
            }, 900);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
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
            refreshRecords();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOne = useCallback((deal: Deal) => {
        setSelectedBoardDeal(deal);
        setSelectedIds(new Set([deal.id]));
        setDrafts({ [deal.id]: toDraft(deal) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((deal: Deal) => {
        setSelectedBoardDeal(deal);
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
                refreshRecords();
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const [tags, setTags] = useState<Tag[]>([]);
    useEffect(() => { getTags().then(setTags).catch(() => setTags([])); }, []);
    useEffect(() => {
        let cancelled = false;
        const dealIds = deals.map((deal) => deal.id);
        const request = dealIds.length === 0 ? Promise.resolve([] as DealRisk[]) : getDealRisks(dealIds);
        request
            .then((risks) => {
                if (!cancelled) setRiskByDealId(new Map(risks.map((r) => [r.dealId, r])));
            })
            .catch(() => {
                if (!cancelled) setRiskByDealId(new Map());
            });
        return () => {
            cancelled = true;
        };
    }, [dataRevision, deals]);
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
    const onBulkSuccess = refreshRecords;

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
            refreshRecords();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToUpdateStatus'));
        }
    }, [refreshRecords, t]);

    const summary = useMemo(() => {
        const m = dealMetrics.byCurrency.find((c) => c.currency === activeCurrency);
        const openCount = m?.openCount ?? 0;
        const openValue = m?.openValue ?? 0;
        const closedActualValue = m?.closedRevenue ?? 0;
        const closedForecastValue = m?.closedForecast ?? 0;
        const forecastAccuracy = closedForecastValue > 0 ? closedActualValue / closedForecastValue : null;
        return { openCount, openValue, closedActualValue, closedForecastValue, forecastAccuracy };
    }, [dealMetrics, activeCurrency]);

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
            key: 'risk',
            label: t('columnRisk'),
            sortable: false,
            render: (d) => <DealRiskPill risk={riskByDealId.get(d.id)} />,
            filter: {
                getValue: (d) => {
                    const level = riskByDealId.get(d.id)?.level;
                    return level && level !== 'none' ? level : null;
                },
                formatValue: (v) =>
                    v === 'high' || v === 'medium' || v === 'low' ? levelLabel(v) : String(v),
            },
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
                                <span className={closed ? 'text-muted-foreground' : 'text-chart-won'}>●</span>
                                {closed ? t('statusClosed') : t('statusOpen')}
                                <ChevronDownIcon className="size-3 text-muted-foreground" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" onClick={(e) => e.stopPropagation()}>
                            {closed ? (
                                <DropdownMenuItem onSelect={() => toggleDealStatus(d, null)}>
                                    <span className="text-chart-won">●</span>
                                    {t('markOpen')}
                                </DropdownMenuItem>
                            ) : (
                                <>
                                    <DropdownMenuItem onSelect={() => toggleDealStatus(d, true)}>
                                        <span className="text-chart-won">●</span>
                                        {t('markWon')}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onSelect={() => toggleDealStatus(d, false)}>
                                        <span className="text-destructive">●</span>
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
    ], [companyById, pipelineById, stageById, riskByDealId, toggleDealStatus, levelLabel, t, locale]);

    const visibleDeals = deals;

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('deal', visibleDeals);

    const facets = useMemo<ColumnFilterFacet[]>(() => {
        const result: ColumnFilterFacet[] = [];
        const openCount = dealFacets.status.find((facet) => facet.key === 'open')?.count ?? 0;
        const closedCount = dealFacets.status
            .filter((s) => s.key === 'won' || s.key === 'lost')
            .reduce((sum, s) => sum + s.count, 0);
        const statusOptions: { key: string; label: string }[] = [];
        if (openCount > 0) statusOptions.push({ key: 'open', label: t('statusOpen') });
        if (closedCount > 0) statusOptions.push({ key: 'closed', label: t('statusClosed') });
        if (statusOptions.length > 0) result.push({ key: 'status', label: t('columnStatus'), options: statusOptions });

        const companyOptions = dealFacets.companies.flatMap((facet) => {
            if (facet.key === FILTER_EMPTY) return [{ key: FILTER_EMPTY, label: t('freelancer') }];
            const label = facet.label ?? companyById.get(Number(facet.key))?.name;
            return label ? [{ key: facet.key, label }] : [];
        });
        if (companyOptions.length > 0) result.push({ key: 'company', label: t('columnCompany'), options: companyOptions });

        const pipelineOptions = dealFacets.pipelines.flatMap((facet) => {
            const pipeline = pipelineById.get(Number(facet.key));
            return pipeline ? [{ key: facet.key, label: pipeline.name }] : [];
        });
        if (pipelineOptions.length > 0) result.push({ key: 'pipeline', label: t('columnPipeline'), options: pipelineOptions });

        const stageOptions = dealFacets.stages.flatMap((facet) => {
            const stage = stageById.get(Number(facet.key));
            return stage ? [{ key: facet.key, label: stage.name }] : [];
        });
        if (stageOptions.length > 0) result.push({ key: 'stage', label: t('columnStage'), options: stageOptions });
        result.push({
            key: 'risk',
            label: t('columnRisk'),
            options: [
                { key: 'high', label: levelLabel('high') },
                { key: 'medium', label: levelLabel('medium') },
                { key: 'low', label: levelLabel('low') },
                { key: 'none', label: t('riskNone') },
            ],
        });
        return result;
    }, [dealFacets, companyById, pipelineById, stageById, levelLabel, t]);
    const hasActiveFilters = query.trim() !== '' || countActiveFilters(activeFilterState) > 0;
    const clearAll = useCallback(() => {
        changeQuery('');
        changeFilters({});
    }, [changeQuery, changeFilters]);
    const memberById = useMemo(() => new Map(members.map((member) => [member.id, member])), [members]);
    const activeMembers = useMemo(() => members.filter((member) => member.status === 'active'), [members]);
    const ownerCounts = useMemo(
        () => new Map(dealFacets.owners?.map((facet) => [facet.key, facet.count]) ?? []),
        [dealFacets.owners],
    );
    const changeOwnerScope = useCallback((values: string[]) => {
        changeFilters({ ...activeFilterState, owner: values });
    }, [activeFilterState, changeFilters]);
    const effectiveOwnerValues = ownerScope.mode === 'me'
        ? [MEMBER_SCOPE_ME]
        : ownerScope.mode === 'unassigned'
            ? [FILTER_EMPTY]
            : ownerScope.memberIds.map(String);
    const ownerChips: FilterChipData[] = effectiveOwnerValues.map((value) => {
        const label = value === MEMBER_SCOPE_ME
            ? ts('me')
            : value === FILTER_EMPTY
                ? ts('unassigned')
                : memberById.get(Number(value))?.displayName ?? value;
        return {
            id: `owner:${value}`,
            label: `${ts('label')}: ${label}`,
            onRemove: () => changeOwnerScope(effectiveOwnerValues.filter((other) => other !== value)),
        };
    });
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => changeQuery('') }] : []),
        ...ownerChips,
        ...facetChips(facets, activeFilterState, changeFilters),
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
        () => ({ filters: activeFilterState, query, sortKey, sortDirection: sortDir }),
        [activeFilterState, query, sortKey, sortDir],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            changeFilters(config.filters ?? {});
            changeQuery(config.query ?? '');
            const nextSortKey = config.sortKey && DEAL_SORT_TOKENS[config.sortKey] ? config.sortKey : null;
            setSortKey(nextSortKey);
            setSortDir(config.sortDirection ?? 'asc');
            setSelectedIds(new Set());
            setPage(1);
        },
        [changeFilters, changeQuery, setSelectedIds],
    );

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
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
                                            {displayCurrency}
                                            <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                                        </button>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end">
                                        {Array.from(currencyCounts.entries())
                                            .sort((a, b) => b[1] - a[1])
                                            .map(([c, n]) => (
                                                <DropdownMenuItem key={c} onSelect={() => changeCurrency(c)}>
                                                    <span className={c === activeCurrency ? 'font-semibold' : ''}>{c}</span>
                                                    <span className="ml-auto text-xs text-muted-foreground">{t('currencyCount', { count: n })}</span>
                                                </DropdownMenuItem>
                                            ))}
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            )}
                            <RecordsActions
                                entity="deals"
                                onNew={() => setNewDialogOpen(true)}
                                newLabel={t('newButton')}
                                newAriaLabel={t('addDeal')}
                                onImported={refreshRecords}
                                onExport={() => exportDealsCsv({ ...serverFilters, currency: activeCurrency ?? undefined, q: deferredQuery || undefined })}
                            />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <section>
                        <SectionHeader title={t('sectionPerformance')} />
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                            <SummaryTile className="sm:col-span-2" label={t('revenueTrend')} value={<DealsRevenueChart series={revenueSeries} currency={displayCurrency} timezone={timezone} />} />
                            <SummaryTile label={t('stageRatio')} value={<StageRatio distribution={stageDistribution} currency={displayCurrency} />} />
                        </div>
                    </section>
                </Rise>

                <Rise delay={0.12}>
                    <section>
                        <SectionHeader title={t('sectionSummary')} />
                        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
                            <SummaryTile
                                label={t('openPipeline')}
                                tooltip={t('openPipelineTooltip')}
                                value={formatCompactCurrency(summary.openValue, displayCurrency, locale)}
                            />
                            <SummaryTile
                                label={t('openDeals')}
                                tooltip={t('openDealsTooltip')}
                                value={String(summary.openCount)}
                            />
                            <SummaryTile
                                label={t('closedForecast')}
                                tooltip={t('closedForecastTooltip')}
                                value={formatCompactCurrency(summary.closedForecastValue, displayCurrency, locale)}
                            />
                            <SummaryTile
                                label={t('closedRevenue')}
                                tooltip={t('closedRevenueTooltip')}
                                value={formatCompactCurrency(summary.closedActualValue, displayCurrency, locale)}
                            />
                            <SummaryTile
                                label={t('forecastAccuracy')}
                                tooltip={t('forecastAccuracyTooltip')}
                                value={summary.forecastAccuracy != null ? `${Math.round(summary.forecastAccuracy * 100)}%` : '—'}
                            />
                        </div>
                    </section>
                </Rise>

                <Rise delay={0.18}>
                    <SavedViewsBar
                        recordType="deal"
                        initialViews={savedViews}
                        currentConfig={currentConfig}
                        onApply={applyView}
                    />
                </Rise>

                <Rise delay={0.24}>
                    <FilterBar
                        reduce={reduce}
                        chips={chips}
                        hasActiveFilters={hasActiveFilters}
                        onClearAll={clearAll}
                        clearAllLabel={tf('clearAll')}
                        search={
                            <SearchField
                                value={query}
                                onChange={changeQuery}
                                onClear={() => changeQuery('')}
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
                                    onClick={() => setDisplayMode('grid')}
                                    aria-label={t('gridView')}
                                    aria-pressed={displayMode === 'grid'}
                                    className={`flex h-8 w-8 items-center justify-center rounded-full transition active:scale-[0.97] ${displayMode === 'grid' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground'}`}
                                >
                                    <Squares2X2Icon className="size-4" />
                                </button>
                                <button
                                    type="button"
                                    onClick={() => setDisplayMode('table')}
                                    aria-label={t('tableView')}
                                    aria-pressed={displayMode === 'table'}
                                    className={`flex h-8 w-8 items-center justify-center rounded-full transition active:scale-[0.97] ${displayMode === 'table' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground'}`}
                                >
                                    <TableCellsIcon className="size-4" />
                                </button>
                                <button
                                    type="button"
                                    onClick={() => setDisplayMode('kanban')}
                                    aria-label={t('kanbanView')}
                                    aria-pressed={displayMode === 'kanban'}
                                    className={`flex h-8 w-8 items-center justify-center rounded-full transition active:scale-[0.97] ${displayMode === 'kanban' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground'}`}
                                >
                                    <ViewColumnsIcon className="size-4" />
                                </button>
                            </div>
                        }
                    >
                        <MemberScopeFilter
                            values={activeFilterState.owner}
                            onChange={changeOwnerScope}
                            members={activeMembers}
                            counts={ownerCounts}
                        />
                        <RecordsFilterPills<Deal>
                            facets={facets}
                            filterState={activeFilterState}
                            onChange={changeFilters}
                        />
                    </FilterBar>
                </Rise>

                {requestError && (
                    <div
                        role="alert"
                        className="rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive"
                    >
                        {requestError}
                    </div>
                )}

                <Rise delay={0.3}>
                    {displayMode === 'kanban' ? (
                        <DealsKanban
                            deals={visibleDeals}
                            pipelines={pipelines}
                            stagesByPipeline={stagesByPipeline}
                            companyById={companyById}
                            pipelineById={pipelineById}
                            stageById={stageById}
                            riskByDealId={riskByDealId}
                            onQuickEdit={quickEditOne}
                            onDelete={deleteOne}
                            onMoved={refreshRecords}
                            query={deferredQuery}
                            currency={activeCurrency ?? undefined}
                            filters={serverFilters}
                            currentUserId={currentUserId}
                            revision={dataRevision}
                            reduce={reduce}
                        />
                    ) : (
                        <RecordsRenderView<Deal>
                            data={visibleDeals}
                            loading={loadingPage}
                            columns={[...columns, ...customColumns.map((c) => ({ ...c, sortable: false }))]}
                            sortState={{ key: sortKey, direction: sortDir, onSortChange: handleSortChange }}
                            addColumnSlot={addColumnSlot}
                            renderCard={(item, { onQuickEdit, onDelete }) => (
                                <DealCard
                                    deal={item}
                                    company={item.company != null ? companyById.get(item.company) : undefined}
                                    pipeline={item.pipeline != null ? pipelineById.get(item.pipeline) : undefined}
                                    stage={item.stage != null ? stageById.get(item.stage) : undefined}
                                    risk={riskByDealId.get(item.id)}
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
                            pagination={{
                                page,
                                pageSize: size,
                                total,
                                onPageChange: changePage,
                                onPageSizeChange: changePageSize,
                            }}
                        />
                    )}
                </Rise>

                <QuickEditDealSheet
                    open={editSheetOpen}
                    onOpenChange={setEditSheetOpen}
                    selectedIds={selectedIds}
                    selectedDeals={selectedDeals}
                    drafts={drafts}
                    updateDraft={updateDraft}
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
        </div>
    );
}
