'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { Button } from '@/components/ui/button';
import RecordsActions from '@/app/components/import/RecordsActions';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    Squares2X2Icon,
    TableCellsIcon,
    TagIcon,
    UserCircleIcon,
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { useRecordPeekController } from '@/app/components/records/useRecordPeekController';
import Rise from '@/app/components/motion/Rise';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import SegmentBuilder, { EMPTY_DEFINITION, isSegmentDefinition, segmentConditionLabel } from '@/app/components/records/SegmentBuilder';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, SegmentedToggle, MemberScopeFilter, interpretMemberScope, MEMBER_SCOPE_ME, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import { type ColumnDef, type ColumnFilterFacet, type SelectionId, FILTER_EMPTY, facetChips, countActiveFilters } from '@/app/components/records/types';
import CompanyCard from '@/app/components/records/companies/CompanyCard';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import { type PendingContact, type PendingContactDraft } from '@/app/components/records/companies/CompanyContactsField';
import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { evaluableSegmentDefinition, hasSegmentConditions } from '@/app/lib/segmentDefinition';
import { createCompany, createContact, getUsers, updateCompany, getCompaniesPage, getCompaniesSegmentPage, getCompanyEngagement, getCompanyFacets, getCompanyIds, getCompanySegmentIds, getCompanyTemperatures, isFieldError, getSegmentFields, getTags, bulkAddTagToCompanies, bulkRemoveTagFromCompanies, bulkDeleteCompanies, bulkAssignCompanyOwner, getActiveWorkspaceMembers, exportCompaniesCsv, uploadCompanyLogo, uploadContactPicture } from '@/app/lib/api';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import BulkAssignOwnerDialog from '@/app/components/records/BulkAssignOwnerDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { type Company, type CompaniesPageParams, type CompanyEngagement, type CompanyFacets, type CreateCompanyPayload, type UpdateCompanyPayload, type User, type CompanyMetrics, type LoadStatus, type RelationshipTemperature, type SavedView, type SavedViewConfig, type SegmentDefinition, type SegmentFields, type RuleBuilderOptions, type Tag, type WorkspaceMember } from '@/app/lib/types';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import { subscribeToRecordMutations } from '@/app/lib/record-mutation-events';

function toDraft(c: Company): CompanyDraft {
    return {
        name: c.name ?? '',
        website: c.website ?? '',
        industry: c.industry ?? '',
        phone: c.phone ?? '',
        address: c.address ?? '',
    };
}

function diffDraft(original: CompanyDraft, draft: CompanyDraft): boolean {
    return (
        original.name !== draft.name ||
        original.website !== draft.website ||
        original.industry !== draft.industry ||
        original.phone !== draft.phone ||
        original.address !== draft.address
    );
}

function metricsFromEngagement(engagement: CompanyEngagement, users: User[]): CompanyMetrics {
    const relatedUserIds = new Set(engagement.relatedUserIds);
    return {
        persons: engagement.persons,
        personCount: engagement.personCount,
        relatedUsers: users.filter((user) => relatedUserIds.has(user.id)),
        relatedUserCount: engagement.relatedUserCount,
        pastRevenue: engagement.pastRevenue,
        projectedRevenue: engagement.projectedRevenue,
        currency: engagement.currency,
        numDeals: engagement.numDeals,
        numTasks: engagement.numTasks,
        numActivities: engagement.numActivities,
        numNotes: engagement.numNotes,
        weeklyEngagement: engagement.weeklyEngagement,
    };
}

const searchFields = (c: Company) => [c.name, c.website, c.industry, c.phone, c.address];

const NO_ITEMS: Company[] = [];
/** Impossible company id (ids are positive) used to force an empty scoped export when a segment matches nothing. */
const NO_MATCH_COMPANY_ID = 0;
const EMPTY_COMPANY_DRAFT: CreateCompanyPayload = {
    name: '',
    website: '',
    industry: '',
    phone: '',
    address: '',
};

function cleanCompanyPayload(payload: CreateCompanyPayload): CreateCompanyPayload {
    return {
        name: payload.name.trim(),
        website: payload.website?.trim() || undefined,
        industry: payload.industry?.trim() || undefined,
        phone: payload.phone?.trim() || undefined,
        address: payload.address?.trim() || undefined,
    };
}

export default function CompaniesBrowser({ savedViews }: { savedViews: SavedView[] }) {
    const router = useRouter();
    const t = useTranslations('CompaniesBrowser');
    const tf = useTranslations('Filters');
    const ts = useTranslations('MemberScope');
    const tSeg = useTranslations('SmartSegments');
    const reduce = useReducedMotion() ?? false;

    const {
        displayMode,
        setDisplayMode,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Company>({ items: NO_ITEMS, storageKey: 'companies:view', searchFields });

    const [definition, setDefinition] = useState<SegmentDefinition>(EMPTY_DEFINITION);
    const [segmentFields, setSegmentFields] = useState<SegmentFields | null>(null);
    const evaluable = useMemo(() => evaluableSegmentDefinition(definition), [definition]);
    const segmentsKey = useMemo(() => JSON.stringify(evaluable), [evaluable]);
    const hasSegments = hasSegmentConditions(evaluable);
    const failedSegmentKeyRef = useRef<string | null>(null);
    const [segmentErrorKey, setSegmentErrorKey] = useState<string | null>(null);
    useEffect(() => {
        getSegmentFields('company').then(setSegmentFields).catch(() => { setSegmentFields(null); toastError(tSeg('fieldsFailed')); });
    }, [tSeg]);

    const ownerScope = useMemo(() => interpretMemberScope(filterState.owner), [filterState.owner]);
    const filterParams = useMemo<{ industry?: string[]; noIndustry?: boolean; scope?: 'me' | 'members' | 'unassigned'; memberIds?: number[] }>(() => {
        const industryFilter = filterState.industry ?? [];
        const industries = industryFilter.filter((k) => k !== FILTER_EMPTY);
        const params: { industry?: string[]; noIndustry?: boolean; scope?: 'me' | 'members' | 'unassigned'; memberIds?: number[] } = {};
        if (industries.length) params.industry = industries;
        if (industryFilter.includes(FILTER_EMPTY)) params.noIndustry = true;
        if (ownerScope.mode !== 'all') params.scope = ownerScope.mode;
        if (ownerScope.mode === 'members') params.memberIds = ownerScope.memberIds;
        return params;
    }, [filterState, ownerScope]);

    const fetchCompaniesPage = useCallback(async (params: CompaniesPageParams) => {
        if (!hasSegments) {
            failedSegmentKeyRef.current = null;
            return getCompaniesPage(params);
        }
        try {
            const response = await getCompaniesSegmentPage({ ...params, definition: evaluable });
            failedSegmentKeyRef.current = null;
            setSegmentErrorKey((key) => key === segmentsKey ? null : key);
            return response;
        } catch (error) {
            if (failedSegmentKeyRef.current !== segmentsKey) {
                failedSegmentKeyRef.current = segmentsKey;
                toastError(tSeg('evaluateFailed'));
            }
            setSegmentErrorKey(segmentsKey);
            throw error;
        }
    }, [evaluable, hasSegments, segmentsKey, tSeg]);

    const {
        items: companies,
        total,
        loading,
        page,
        setPage,
        size,
        setSize,
        query,
        setQuery,
        applyQuery,
        sortKey,
        sortDirection,
        onSortChange: changeServerSort,
        applySort: applyServerSort,
        revision,
        reload,
    } = useServerRecords<Company, CompaniesPageParams>(fetchCompaniesPage, filterParams);

    const selectedCompanies = useMemo(() => companies.filter((c) => selectedIds.has(c.id)), [companies, selectedIds]);
    const selectedCompanyIds = useMemo(() => Array.from(selectedIds).map(Number), [selectedIds]);

    const filterSignature = useMemo(
        () => JSON.stringify([filterParams, query, hasSegments ? segmentsKey : null, segmentErrorKey === segmentsKey]),
        [filterParams, query, hasSegments, segmentsKey, segmentErrorKey],
    );
    const filterSignatureRef = useRef(filterSignature);
    useEffect(() => {
        filterSignatureRef.current = filterSignature;
    }, [filterSignature]);
    const [matchedSignature, setMatchedSignature] = useState<string | null>(null);
    const allMatchingActive = selectedIds.size > 0 && matchedSignature === filterSignature;
    const selectAllRequestRef = useRef(0);
    const [selectingAll, setSelectingAll] = useState(false);
    const clearSelection = useCallback(() => {
        selectAllRequestRef.current += 1;
        setSelectingAll(false);
        setMatchedSignature(null);
        setSelectedIds(new Set());
    }, [setSelectedIds]);
    const onSortChange = useCallback((key: string) => {
        clearSelection();
        changeServerSort(key);
    }, [clearSelection, changeServerSort]);
    const applySort = useCallback((key: string | null, direction: 'asc' | 'desc') => {
        clearSelection();
        applyServerSort(key, direction);
    }, [clearSelection, applyServerSort]);
    const handleSelectedIdsChange = useCallback((ids: Set<SelectionId>) => {
        selectAllRequestRef.current += 1;
        setMatchedSignature(null);
        setSelectedIds(allMatchingActive ? new Set() : ids);
    }, [allMatchingActive, setSelectedIds]);
    const selectionScope = `${page}:${size}:${sortKey ?? ''}:${sortDirection}:${revision}:${filterSignature}`;
    const previousSelectionScopeRef = useRef(selectionScope);
    useEffect(() => {
        const scopeChanged = previousSelectionScopeRef.current !== selectionScope;
        previousSelectionScopeRef.current = selectionScope;
        if (scopeChanged && !allMatchingActive) clearSelection();
    }, [selectionScope, allMatchingActive, clearSelection]);

    const [metricsByCompanyId, setMetricsByCompanyId] = useState<Map<number, CompanyMetrics>>(new Map());
    const [metricsStatusByCompanyId, setMetricsStatusByCompanyId] =
        useState<Map<number, LoadStatus>>(new Map());
    const metricsRequestRef = useRef(new Map<number, number>());
    const metricsGenerationRef = useRef(0);
    const usersPromiseRef = useRef<Promise<User[]> | null>(null);
    useEffect(() => () => {
        metricsGenerationRef.current += 1;
    }, []);

    const resetMetrics = useCallback(() => {
        metricsGenerationRef.current += 1;
        metricsRequestRef.current.clear();
        setMetricsByCompanyId(new Map());
        setMetricsStatusByCompanyId(new Map());
    }, []);

    const [companyFacets, setCompanyFacets] = useState<CompanyFacets | null>(null);
    const loadFacets = useCallback(() => {
        getCompanyFacets().then(setCompanyFacets).catch(() => setCompanyFacets(null));
    }, []);
    useEffect(() => { loadFacets(); }, [loadFacets]);
    const refresh = useCallback(() => {
        clearSelection();
        resetMetrics();
        reload();
        loadFacets();
    }, [clearSelection, resetMetrics, reload, loadFacets]);

    useEffect(() => subscribeToRecordMutations((entity) => {
        if (entity === 'company') refresh();
    }), [refresh]);

    const selectAllMatching = useCallback(async () => {
        const requestId = selectAllRequestRef.current + 1;
        selectAllRequestRef.current = requestId;
        const requestSignature = filterSignature;
        setSelectingAll(true);
        try {
            const params = { ...filterParams, q: query || undefined };
            const ids = hasSegments
                ? await getCompanySegmentIds({ ...params, definition: evaluable })
                : await getCompanyIds(params);
            if (requestId !== selectAllRequestRef.current || requestSignature !== filterSignatureRef.current) return;
            setSelectedIds(new Set(ids));
            setMatchedSignature(requestSignature);
        } catch (err) {
            if (requestId !== selectAllRequestRef.current) return;
            if (hasSegments) {
                if (failedSegmentKeyRef.current !== segmentsKey) toastError(tSeg('evaluateFailed'));
                failedSegmentKeyRef.current = segmentsKey;
                setSegmentErrorKey(segmentsKey);
            } else {
                toastError(err instanceof Error ? err.message : t('toastSelectAllFailed'));
            }
        } finally {
            if (requestId === selectAllRequestRef.current) setSelectingAll(false);
        }
    }, [filterParams, query, hasSegments, evaluable, segmentsKey, filterSignature, setSelectedIds, t, tSeg]);

    const exportCompanies = useCallback(async () => {
        const params = { ...filterParams, q: query.trim() || undefined };
        if (!hasSegments) {
            await exportCompaniesCsv(params);
            return;
        }
        const matched = await getCompanySegmentIds({ ...params, definition: evaluable });
        await exportCompaniesCsv({ ...params, ids: matched.length ? matched : [NO_MATCH_COMPANY_ID] });
    }, [filterParams, query, hasSegments, evaluable]);

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, CompanyDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const [newDialogOpen, setNewDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newPayload, setNewPayload] = useState<CreateCompanyPayload>(EMPTY_COMPANY_DRAFT);
    const [logoFile, setLogoFile] = useState<File | null>(null);
    const [pendingContacts, setPendingContacts] = useState<PendingContact[]>([]);
    const newCompanyCloseTimerRef = useRef<number | null>(null);
    const newCompanyGenerationRef = useRef(0);
    const invalidateNewCompanyClose = useCallback(() => {
        newCompanyGenerationRef.current += 1;
        if (newCompanyCloseTimerRef.current == null) return;
        window.clearTimeout(newCompanyCloseTimerRef.current);
        newCompanyCloseTimerRef.current = null;
    }, []);
    useEffect(() => () => invalidateNewCompanyClose(), [invalidateNewCompanyClose]);

    const addPendingContact = (draft: PendingContactDraft) =>
        setPendingContacts((prev) => [...prev, { tempId: crypto.randomUUID(), ...draft }]);
    const updatePendingContact = (tempId: string, draft: PendingContactDraft) =>
        setPendingContacts((prev) => prev.map((c) => (c.tempId === tempId ? { tempId, ...draft } : c)));
    const removePendingContact = (tempId: string) =>
        setPendingContacts((prev) => prev.filter((c) => c.tempId !== tempId));
    const createPendingContact = async (c: PendingContact, companyId: number) => {
        const payload = {
            name: c.name.trim(),
            email: c.email.trim(),
            phone: c.phone.trim(),
            title: c.title.trim(),
            companyId,
        };
        const newContact = await createContact(payload);
        if (c.imageFile) {
            await uploadContactPicture(newContact.id, c.imageFile).catch(() => undefined);
        }
        return newContact;
    };

    const [tempByCompanyId, setTempByCompanyId] = useState<Map<number, RelationshipTemperature>>(new Map());
    useEffect(() => {
        let cancelled = false;
        getCompanyTemperatures(companies.map((company) => company.id))
            .then((temps) => {
                if (!cancelled) setTempByCompanyId(new Map(temps.map((temp) => [temp.id, temp])));
            })
            .catch(() => {
                if (!cancelled) setTempByCompanyId(new Map());
            });
        return () => {
            cancelled = true;
        };
    }, [companies]);

    const ensureMetricsLoaded = useCallback((companyId: number) => {
        const status = metricsStatusByCompanyId.get(companyId);
        if (status === 'loading' || status === 'ready') return;
        const requestId = (metricsRequestRef.current.get(companyId) ?? 0) + 1;
        const generation = metricsGenerationRef.current;
        metricsRequestRef.current.set(companyId, requestId);
        setMetricsStatusByCompanyId((current) => new Map(current).set(companyId, 'loading'));
        usersPromiseRef.current ??= getUsers();
        Promise.all([getCompanyEngagement(companyId), usersPromiseRef.current])
            .then(([engagement, users]) => {
                if (generation !== metricsGenerationRef.current
                    || metricsRequestRef.current.get(companyId) !== requestId) return;
                setMetricsByCompanyId((current) =>
                    new Map(current).set(companyId, metricsFromEngagement(engagement, users)));
                setMetricsStatusByCompanyId((current) => new Map(current).set(companyId, 'ready'));
            })
            .catch(() => {
                if (generation !== metricsGenerationRef.current
                    || metricsRequestRef.current.get(companyId) !== requestId) return;
                usersPromiseRef.current = null;
                setMetricsByCompanyId((current) => {
                    const next = new Map(current);
                    next.delete(companyId);
                    return next;
                });
                setMetricsStatusByCompanyId((current) => new Map(current).set(companyId, 'error'));
                toastError(t('toastMetricsLoadFailed'));
            });
    }, [metricsStatusByCompanyId, t]);

    const openNewDialog = () => {
        invalidateNewCompanyClose();
        setNewPayload(EMPTY_COMPANY_DRAFT);
        setLogoFile(null);
        setPendingContacts([]);
        setCreationSucceeded(false);
        setNewDialogOpen(true);
    };

    const closeNewDialog = (open: boolean) => {
        invalidateNewCompanyClose();
        setNewDialogOpen(open);
    };

    const createNewCompany = async () => {
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const companyPayload = cleanCompanyPayload(newPayload);
            const created = await createCompany(companyPayload);
            let logoUploadFailed = false;
            if (logoFile) {
                try {
                    await uploadCompanyLogo(created.id, logoFile);
                } catch {
                    logoUploadFailed = true;
                }
            }
            if (pendingContacts.length > 0) {
                const results = await Promise.allSettled(pendingContacts.map((c) => createPendingContact(c, created.id)));
                const failed = results.filter((r) => r.status === 'rejected').length;
                if (failed === pendingContacts.length) {
                    toastError(t('toastContactsAllFailed', { count: failed }));
                } else if (failed > 0) {
                    toastError(t('toastContactsPartial', { succeeded: pendingContacts.length - failed, total: pendingContacts.length }));
                }
            }
            toastSuccess(t('toastCompanyCreated'));
            if (logoUploadFailed) toastError(t('toastLogoUploadFailed'));
            setIsCreating(false);
            setCreationSucceeded(true);
            invalidateNewCompanyClose();
            const closeGeneration = newCompanyGenerationRef.current;
            newCompanyCloseTimerRef.current = window.setTimeout(() => {
                if (newCompanyGenerationRef.current !== closeGeneration) return;
                newCompanyCloseTimerRef.current = null;
                closeNewDialog(false);
                refresh();
            }, 850);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            toastError(t('toastCreateFailed'));
        } finally {
            setIsCreating(false);
        }
    };

    const openEditSheet = () => {
        const next: Record<number, CompanyDraft> = {};
        for (const c of selectedCompanies) next[c.id] = toDraft(c);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<CompanyDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedCompanies.filter((c) => {
            const draft = drafts[c.id];
            return draft && diffDraft(toDraft(c), draft);
        });

        if (changed.length === 0) {
            toast.info(t('toastNoChanges'));
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((c) => !drafts[c.id].name.trim());
        if (invalid) {
            toast.error(t('toastNameRequired', { name: invalid.name }));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((c) => {
                    const d = drafts[c.id];
                    const payload: UpdateCompanyPayload = {
                        name: d.name.trim(),
                        website: d.website.trim() || undefined,
                        industry: d.industry.trim() || undefined,
                        phone: d.phone.trim() || undefined,
                        address: d.address.trim() || undefined,
                    };
                    return updateCompany(c.id, payload);
                }),
            );
            toastSuccess(
                changed.length === 1 ? t('toastCompanyUpdated') : t('toastCompaniesUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastSaveFailed'));
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOne = useCallback((company: Company) => {
        setMatchedSignature(null);
        setSelectedIds(new Set([company.id]));
        setDrafts({ [company.id]: toDraft(company) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((company: Company) => {
        setMatchedSignature(null);
        setSelectedIds(new Set([company.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const confirmDelete = async () => {
        if (selectedCompanyIds.length === 0) return;
        setIsDeleting(true);
        try {
            const result = await bulkDeleteCompanies(selectedCompanyIds);
            const anySucceeded = notifyBulkResult(result, {
                success: (count) => count === 1 ? t('toastCompanyDeleted') : t('toastCompaniesDeleted', { count }),
                partial: (succeeded, total) => t('toastCompaniesDeletedPartial', { succeeded, total }),
                failure: (failed) => t('toastCompaniesDeleteFailed', { failed }),
            });
            setDeleteDialogOpen(false);
            if (anySucceeded) {
                setSelectedIds(new Set());
                refresh();
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastDeleteFailed'));
        } finally {
            setIsDeleting(false);
        }
    };

    const [tags, setTags] = useState<Tag[]>([]);
    useEffect(() => { getTags().then(setTags).catch(() => setTags([])); }, []);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    useEffect(() => { getActiveWorkspaceMembers().then(setMembers).catch(() => setMembers([])); }, []);
    const memberById = useMemo(() => new Map(members.map((member) => [member.id, member])), [members]);
    const activeMembers = useMemo(() => members.filter((member) => member.status === 'active'), [members]);
    const segmentOptions = useMemo<RuleBuilderOptions>(() => ({
        stages: [],
        owners: activeMembers.map((member) => ({ id: member.id, name: member.displayName })),
        companies: [],
    }), [activeMembers]);
    const ownerCounts = useMemo(
        () => new Map(companyFacets?.owners?.map((facet) => [facet.key, facet.count]) ?? []),
        [companyFacets],
    );
    const changeOwnerScope = useCallback((values: string[]) => {
        setFilterState({ ...filterState, owner: values });
    }, [filterState, setFilterState]);
    const [bulkOwnerOpen, setBulkOwnerOpen] = useState(false);
    const [bulkTag, setBulkTag] = useState<{ open: boolean; mode: 'add' | 'remove' }>({ open: false, mode: 'add' });
    const applyBulkTag = useCallback((tagId: number) => {
        return bulkTag.mode === 'add'
            ? bulkAddTagToCompanies(selectedCompanyIds, tagId)
            : bulkRemoveTagFromCompanies(selectedCompanyIds, tagId);
    }, [bulkTag.mode, selectedCompanyIds]);
    const onBulkTagSuccess = useCallback(() => { setSelectedIds(new Set()); refresh(); }, [setSelectedIds, refresh]);

    const viewSelected = () => {
        if (selectedCompanies.length === 1) {
            router.push(`/records/companies/${selectedCompanies[0].id}`);
        } else {
            selectedCompanies.forEach((c) => window.open(`/records/companies/${c.id}`, '_blank'));
        }
    };

    const columns: ColumnDef<Company>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (c) => c.name ?? null, widthClass: 'min-w-48' },
        {
            key: 'warmth',
            label: t('columnWarmth'),
            getSortValue: (c) => tempByCompanyId.get(c.id)?.score ?? null,
            sortable: false,
            render: (c) => <TemperaturePill temp={tempByCompanyId.get(c.id)} />,
        },
        {
            key: 'owner',
            label: t('columnOwner'),
            sortable: false,
            render: (c) => (c.ownerId != null ? memberById.get(c.ownerId)?.displayName ?? '' : ''),
        },
        {
            key: 'website',
            label: t('columnWebsite'),
            getSortValue: (c) => c.website ?? null,
            copyable: { label: t('columnWebsite'), getValue: (c) => c.website },
        },
        {
            key: 'industry',
            label: t('columnIndustry'),
            getSortValue: (c) => c.industry ?? null,
        },
        {
            key: 'phone',
            label: t('columnPhone'),
            getSortValue: (c) => c.phone ?? null,
            copyable: { label: t('columnPhone'), getValue: (c) => c.phone },
        },
        {
            key: 'address',
            label: t('columnAddress'),
            getSortValue: (c) => c.address ?? null,
            copyable: { label: t('columnAddress'), getValue: (c) => c.address },
        },
        {
            key: 'createdAt',
            label: t('columnCreated'),
            getSortValue: (c) => (c.createdAt ? Date.parse(c.createdAt) : null),
            render: (c) => c.createdAt,
        },
        {
            key: 'updatedAt',
            label: t('columnUpdated'),
            getSortValue: (c) => (c.updatedAt ? Date.parse(c.updatedAt) : null),
            render: (c) => c.updatedAt,
        },
    ], [t, tempByCompanyId, memberById]);

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('company', companies);

    const facets = useMemo<ColumnFilterFacet[]>(() => {
        if (!companyFacets) return [];
        const options = companyFacets.industries.map((name) => ({ key: name, label: name }));
        if (companyFacets.hasNoIndustry) options.push({ key: FILTER_EMPTY, label: t('filterNoIndustry') });
        return options.length ? [{ key: 'industry', label: t('columnIndustry'), options }] : [];
    }, [companyFacets, t]);
    const hasActiveFilters = query.trim() !== '' || countActiveFilters(filterState) > 0 || hasSegments;
    const clearAll = useCallback(() => { setQuery(''); setFilterState({}); setDefinition(EMPTY_DEFINITION); }, [setQuery, setFilterState]);
    const resolveTagName = useCallback(
        (id: string) => segmentFields?.tags.find((tag) => String(tag.id) === id)?.name ?? id,
        [segmentFields],
    );
    const resolveOwnerName = useCallback(
        (id: string) => memberById.get(Number(id))?.displayName ?? id,
        [memberById],
    );
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
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...ownerChips,
        ...facetChips(facets, filterState, setFilterState),
        ...definition.conditions.flatMap((condition, index) =>
            condition.type === 'predicate' || (condition.value ?? '').trim() !== ''
                ? [{
                    id: `segment:${index}`,
                    label: segmentConditionLabel(condition, tSeg, resolveTagName, resolveOwnerName),
                    onRemove: () => setDefinition({ ...definition, conditions: definition.conditions.filter((_, i) => i !== index) }),
                }]
                : [],
        ),
    ];

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
            {!allMatchingActive && (
                <>
                    <Button variant="outline" size="sm" onClick={viewSelected}>
                        <EyeIcon className="size-4" />
                        {t('view')}
                    </Button>
                    <Button variant="outline" size="sm" onClick={openEditSheet}>
                        <PencilIcon className="size-4" />
                        {t('quickEdit')}
                    </Button>
                </>
            )}
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="sm">
                        <EllipsisVerticalIcon className="size-4" />
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'add' }); }}>
                        <TagIcon />
                        {t('addTag')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'remove' }); }}>
                        <TagIcon />
                        {t('removeTag')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkOwnerOpen(true); }}>
                        <UserCircleIcon />
                        {t('assignOwner')}
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
        () => ({ filters: filterState, query, sortKey: sortKey === 'warmth' ? null : sortKey, sortDirection, segments: definition }),
        [filterState, query, sortKey, sortDirection, definition],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            setFilterState(config.filters ?? {});
            applyQuery(config.query ?? '');
            applySort(config.sortKey === 'warmth' ? null : config.sortKey ?? null, config.sortDirection ?? 'asc');
            setDefinition(isSegmentDefinition(config.segments) ? config.segments : EMPTY_DEFINITION);
        },
        [setFilterState, applyQuery, applySort],
    );

    const peek = useRecordPeekController('company', companies);

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <div className="flex items-center justify-between">
                        <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                        <div className="flex items-center gap-2">
                            <RecordsActions
                                entity="companies"
                                onNew={openNewDialog}
                                newLabel={t('new')}
                                newAriaLabel={t('addCompanyAriaLabel')}
                                onImported={refresh}
                                onExport={exportCompanies}
                            />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <SavedViewsBar
                        recordType="company"
                        initialViews={savedViews}
                        currentConfig={currentConfig}
                        onApply={applyView}
                    />
                </Rise>

                <Rise delay={0.12}>
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
                            <div className="flex items-center gap-2">
                                <SegmentBuilder definition={definition} fields={segmentFields} options={segmentOptions} onChange={setDefinition} />
                                {displayMode === 'grid' && (
                                    <RecordsSortMenu
                                        columns={columns}
                                        sortKey={sortKey}
                                        sortDirection={sortDirection}
                                        onSortChange={onSortChange}
                                    />
                                )}
                                <SegmentedToggle
                                    ariaLabel={t('displayModeAriaLabel')}
                                    value={displayMode}
                                    onChange={setDisplayMode}
                                    options={[
                                        { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('gridViewAriaLabel') },
                                        { value: 'table', icon: <TableCellsIcon className="size-4" />, ariaLabel: t('tableViewAriaLabel') },
                                    ]}
                                />
                            </div>
                        }
                    >
                        <MemberScopeFilter
                            values={filterState.owner}
                            onChange={changeOwnerScope}
                            members={activeMembers}
                            counts={ownerCounts}
                        />
                        <RecordsFilterPills<Company>
                            facets={facets}
                            filterState={filterState}
                            onChange={setFilterState}
                        />
                    </FilterBar>
                </Rise>

                {(() => {
                    const pageFullySelected = companies.length > 0 && companies.every((company) => selectedIds.has(company.id));
                    const allMatchingSelected = allMatchingActive && total > companies.length && selectedIds.size >= total;
                    const canSelectAllMatching = hasActiveFilters && pageFullySelected && total > companies.length && selectedIds.size < total;
                    if (!canSelectAllMatching && !allMatchingSelected) return null;
                    return (
                        <div className="flex flex-wrap items-center justify-center gap-x-2 gap-y-1 rounded-lg bg-muted px-4 py-2 text-sm text-muted-foreground ring-1 ring-border">
                            {allMatchingSelected ? (
                                <>
                                    <span>{t('allMatchingSelected', { total })}</span>
                                    <button type="button" onClick={() => { setSelectedIds(new Set()); setMatchedSignature(null); }} className="font-medium text-brand transition hover:underline">
                                        {t('clearSelection')}
                                    </button>
                                </>
                            ) : (
                                <>
                                    <span>{t('pageSelected', { count: selectedCompanyIds.length })}</span>
                                    <button
                                        type="button"
                                        onClick={selectAllMatching}
                                        disabled={selectingAll || loading}
                                        className="font-medium text-brand transition hover:underline disabled:opacity-50"
                                    >
                                        {selectingAll ? t('selecting') : t('selectAllMatching', { total })}
                                    </button>
                                </>
                            )}
                        </div>
                    );
                })()}

                <Rise delay={0.18}>
                    <RecordsRenderView<Company>
                        data={companies}
                        loading={loading}
                        columns={[...columns, ...customColumns.map((c) => ({ ...c, sortable: false }))]}
                        addColumnSlot={addColumnSlot}
                        renderCard={(item, { onQuickEdit, onDelete }) => (
                            <CompanyCard
                                company={item}
                                ownerName={item.ownerId != null ? memberById.get(item.ownerId)?.displayName : undefined}
                                metrics={metricsByCompanyId.get(item.id)}
                                metricsStatus={metricsStatusByCompanyId.get(item.id) ?? 'idle'}
                                onFirstExpand={() => ensureMetricsLoaded(item.id)}
                                onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                onDelete={onDelete ? () => onDelete(item) : undefined}
                            />
                        )}
                        renderAvatar={(item) => <CompanyAvatar company={item} />}
                        detailPath={(item) => `/records/companies/${item.id}`}
                        onRowClick={(item) => peek.openPeek(item.id)}
                        activeId={peek.activeId}
                        displayMode={displayMode}
                        selectedIds={selectedIds}
                        onSelectedIdsChange={handleSelectedIdsChange}
                        onQuickEdit={quickEditOne}
                        onDelete={deleteOne}
                        gridClassName="grid grid-cols-1 gap-3"
                        entityLabel={t('entityLabel')}
                        selectionActions={selectionActions}
                        sortState={{ key: sortKey, direction: sortDirection, onSortChange }}
                        pagination={{ page, pageSize: size, total, onPageChange: setPage, onPageSizeChange: setSize }}
                    />
                </Rise>

                {peek.drawer}

                <QuickEditCompanySheet
                    open={editSheetOpen}
                    onOpenChange={setEditSheetOpen}
                    selectedIds={selectedIds}
                    selectedCompanies={selectedCompanies}
                    drafts={drafts}
                    updateDraft={updateDraft}
                    isSaving={isSaving}
                    saveEdits={saveEdits}
                />

                <NewCompanyDialog
                    open={newDialogOpen}
                    onOpenChange={closeNewDialog}
                    payload={newPayload}
                    setPayload={setNewPayload}
                    logoFile={logoFile}
                    setLogoFile={setLogoFile}
                    isCreating={isCreating}
                    isSuccess={creationSucceeded}
                    existingCompanies={companies}
                    createNewCompany={createNewCompany}
                    pendingContacts={pendingContacts}
                    addPendingContact={addPendingContact}
                    updatePendingContact={updatePendingContact}
                    removePendingContact={removePendingContact}
                />

                <DeleteRecordDialog
                    open={deleteDialogOpen}
                    onOpenChange={setDeleteDialogOpen}
                    selectedIds={selectedIds}
                    selectedItems={selectedCompanies}
                    entityLabel={t('entityLabel')}
                    getDisplayName={(c) => c.name}
                    isDeleting={isDeleting}
                    confirmDelete={confirmDelete}
                />

                <BulkTagDialog
                    open={bulkTag.open}
                    onOpenChange={(open) => setBulkTag((s) => ({ ...s, open }))}
                    mode={bulkTag.mode}
                    count={selectedCompanyIds.length}
                    tags={tags}
                    messages={{
                        success: (count) => t(bulkTag.mode === 'add' ? 'toastTagAdded' : 'toastTagRemoved', { count }),
                        partial: (succeeded, total) => t(bulkTag.mode === 'add' ? 'toastTagAddedPartial' : 'toastTagRemovedPartial', { succeeded, total }),
                        failure: (failed) => t('toastTagFailed', { failed }),
                    }}
                    onApply={applyBulkTag}
                    onSuccess={onBulkTagSuccess}
                />

                <BulkAssignOwnerDialog
                    open={bulkOwnerOpen}
                    onOpenChange={setBulkOwnerOpen}
                    count={selectedCompanyIds.length}
                    members={activeMembers}
                    messages={{
                        success: (count) => t('toastOwnerAssigned', { count }),
                        partial: (succeeded, total) => t('toastOwnerAssignedPartial', { succeeded, total }),
                        failure: (failed) => t('toastOwnerFailed', { failed }),
                    }}
                    onApply={(ownerId) => bulkAssignCompanyOwner(selectedCompanyIds, ownerId)}
                    onSuccess={onBulkTagSuccess}
                />
            </div>
        </div>
    );
}
