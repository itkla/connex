'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
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
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import Rise from '@/app/components/motion/Rise';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import SegmentBuilder, { EMPTY_DEFINITION, isSegmentDefinition, segmentConditionLabel } from '@/app/components/records/SegmentBuilder';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, SegmentedToggle, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import { type ColumnDef, type ColumnFilterFacet, type SelectionId, FILTER_EMPTY, facetChips, countActiveFilters } from '@/app/components/records/types';
import CompanyCard from '@/app/components/records/companies/CompanyCard';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import { type PendingContact, type PendingContactDraft } from '@/app/components/records/companies/CompanyContactsField';
import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { createCompany, createContact, updateContact, getUsers, getTasks, getDeals, updateCompany, getActivities, getNotes, getCompaniesPage, getCompanyFacets, getCompanyIds, getCompanyTemperatures, isFieldError, evaluateSegments, getSegmentFields, getTags, bulkAddTagToCompanies, bulkRemoveTagFromCompanies, bulkDeleteCompanies } from '@/app/lib/api';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { uploadCompanyLogo, uploadContactPicture, pickDominantCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { type Company, type CompaniesPageParams, type CompanyFacets, type CreateCompanyPayload, type UpdateCompanyPayload, type Contact, type Activity, type Note, type Task, type User, type Deal, type CompanyMetrics, type LoadStatus, type RelationshipTemperature, type SavedView, type SavedViewConfig, type SegmentDefinition, type SegmentFields, type Tag } from '@/app/lib/types';
import { getContacts } from '@/app/lib/api';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import { isDealClosed } from '@/app/components/records/deals/dealOutcome';

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

const searchFields = (c: Company) => [c.name, c.website, c.industry, c.phone, c.address];

const NO_ITEMS: Company[] = [];

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
    const [segmentResult, setSegmentResult] = useState<{ key: string; ids: Set<number> | null } | null>(null);
    const evaluable = useMemo<SegmentDefinition>(
        () => ({
            match: definition.match,
            conditions: definition.conditions.filter((condition) => condition.type === 'predicate' || (condition.value ?? '').trim() !== ''),
        }),
        [definition],
    );
    const segmentsKey = useMemo(() => JSON.stringify(evaluable), [evaluable]);
    useEffect(() => {
        getSegmentFields('company').then(setSegmentFields).catch(() => { setSegmentFields(null); toastError(tSeg('fieldsFailed')); });
    }, [tSeg]);
    useEffect(() => {
        if (evaluable.conditions.length === 0) return;
        if (segmentResult?.key === segmentsKey) return;
        let active = true;
        const timer = setTimeout(() => {
            evaluateSegments('company', evaluable)
                .then((result) => { if (active) setSegmentResult({ key: segmentsKey, ids: new Set(result.ids) }); })
                .catch(() => { if (active) { setSegmentResult({ key: segmentsKey, ids: null }); toastError(tSeg('evaluateFailed')); } });
        }, 300);
        return () => { active = false; clearTimeout(timer); };
    }, [evaluable, segmentsKey, segmentResult, tSeg]);
    const activeSegmentIds = evaluable.conditions.length > 0 && segmentResult?.key === segmentsKey ? segmentResult.ids : null;
    const segmentsLoading = evaluable.conditions.length > 0 && segmentResult?.key !== segmentsKey;

    const filterParams = useMemo<{ industry?: string[]; noIndustry?: boolean; ids?: number[] }>(() => {
        const industryFilter = filterState.industry ?? [];
        const industries = industryFilter.filter((k) => k !== FILTER_EMPTY);
        const params: { industry?: string[]; noIndustry?: boolean; ids?: number[] } = {};
        if (industries.length) params.industry = industries;
        if (industryFilter.includes(FILTER_EMPTY)) params.noIndustry = true;
        if (evaluable.conditions.length > 0) {
            params.ids = activeSegmentIds ? (activeSegmentIds.size ? Array.from(activeSegmentIds) : [0]) : [0];
        }
        return params;
    }, [filterState, evaluable, activeSegmentIds]);

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
        onSortChange,
        applySort,
        reload,
    } = useServerRecords<Company, CompaniesPageParams>(getCompaniesPage, filterParams);

    const selectedCompanies = useMemo(() => companies.filter((c) => selectedIds.has(c.id)), [companies, selectedIds]);
    const selectedCompanyIds = useMemo(() => Array.from(selectedIds).map(Number), [selectedIds]);

    const filterSignature = useMemo(() => JSON.stringify([filterParams, query]), [filterParams, query]);
    const [matchedSignature, setMatchedSignature] = useState<string | null>(null);
    const allMatchingActive = matchedSignature === filterSignature;
    const handleSelectedIdsChange = useCallback((ids: Set<SelectionId>) => {
        if (ids.size === 0) setMatchedSignature(null);
        setSelectedIds(ids);
    }, [setSelectedIds]);
    useEffect(() => { if (!allMatchingActive) setSelectedIds(new Set()); }, [companies, allMatchingActive, setSelectedIds]);

    const [companyFacets, setCompanyFacets] = useState<CompanyFacets | null>(null);
    const loadFacets = useCallback(() => {
        getCompanyFacets().then(setCompanyFacets).catch(() => setCompanyFacets(null));
    }, []);
    useEffect(() => { loadFacets(); }, [loadFacets]);
    const refresh = useCallback(() => { reload(); loadFacets(); }, [reload, loadFacets]);

    const [selectingAll, setSelectingAll] = useState(false);
    const selectAllMatching = useCallback(async () => {
        setSelectingAll(true);
        try {
            const ids = await getCompanyIds({ ...filterParams, q: query || undefined });
            setSelectedIds(new Set(ids));
            setMatchedSignature(filterSignature);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastSelectAllFailed'));
        } finally {
            setSelectingAll(false);
        }
    }, [filterParams, query, filterSignature, setSelectedIds, t]);

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, CompanyDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const emptyDraft: CreateCompanyPayload = { name: '', website: '', industry: '', phone: '', address: '' };
    const [newDialogOpen, setNewDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newPayload, setNewPayload] = useState<CreateCompanyPayload>(emptyDraft);
    const [logoFile, setLogoFile] = useState<File | null>(null);
    const [pendingContacts, setPendingContacts] = useState<PendingContact[]>([]);

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
            const imageUrl = await uploadContactPicture(newContact.id, c.imageFile).catch(() => null);
            if (imageUrl) await updateContact(newContact.id, { ...payload, imageUrl }).catch(() => undefined);
        }
        return newContact;
    };

    const [allContacts, setAllContacts] = useState<Contact[]>([]);
    const [allDeals, setAllDeals] = useState<Deal[]>([]);
    const [allTasks, setAllTasks] = useState<Task[]>([]);
    const [allActivities, setAllActivities] = useState<Activity[]>([]);
    const [allNotes, setAllNotes] = useState<Note[]>([]);
    const [allUsers, setAllUsers] = useState<User[]>([]);
    const [metricsStatus, setMetricsStatus] = useState<LoadStatus>('idle');

    const [tempByCompanyId, setTempByCompanyId] = useState<Map<number, RelationshipTemperature>>(new Map());
    useEffect(() => {
        getCompanyTemperatures(companies.map((company) => company.id))
            .then((temps) => setTempByCompanyId(new Map(temps.map((temp) => [temp.id, temp]))))
            .catch(() => setTempByCompanyId(new Map()));
    }, [companies]);

    const ensureMetricsLoaded = useCallback(() => {
        if (metricsStatus === 'loading' || metricsStatus === 'ready') return;
        setMetricsStatus('loading');
        Promise.all([getContacts({}), getDeals(), getTasks(), getActivities(), getNotes(), getUsers()])
            .then(([contacts, deals, tasks, activities, notes, users]) => {
                setAllContacts(contacts);
                setAllDeals(deals);
                setAllTasks(tasks);
                setAllActivities(activities);
                setAllNotes(notes);
                setAllUsers(users);
                setMetricsStatus('ready');
            })
            .catch(() => {
                setMetricsStatus('error');
                toastError(t('toastMetricsLoadFailed'));
            });
    }, [metricsStatus, t]);

    const closeNewDialog = (open: boolean) => {
        setNewDialogOpen(open);
        if (!open) {
            setNewPayload(emptyDraft);
            setLogoFile(null);
            setPendingContacts([]);
            setCreationSucceeded(false);
        }
    };

    const createNewCompany = async () => {
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const companyPayload = cleanCompanyPayload(newPayload);
            const created = await createCompany(companyPayload);
            if (logoFile) {
                const logoUrl = await uploadCompanyLogo(created.id, logoFile);
                await updateCompany(created.id, { ...companyPayload, logoUrl });
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
            setIsCreating(false);
            setCreationSucceeded(true);
            setTimeout(() => {
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
                        logoUrl: c.logoUrl || undefined,
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
        setSelectedIds(new Set([company.id]));
        setDrafts({ [company.id]: toDraft(company) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((company: Company) => {
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
    ], [t, tempByCompanyId]);

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('company', companies);

    const facets = useMemo<ColumnFilterFacet[]>(() => {
        if (!companyFacets) return [];
        const options = companyFacets.industries.map((name) => ({ key: name, label: name }));
        if (companyFacets.hasNoIndustry) options.push({ key: FILTER_EMPTY, label: t('filterNoIndustry') });
        return options.length ? [{ key: 'industry', label: t('columnIndustry'), options }] : [];
    }, [companyFacets, t]);
    const hasActiveFilters = query.trim() !== '' || countActiveFilters(filterState) > 0 || evaluable.conditions.length > 0;
    const clearAll = useCallback(() => { setQuery(''); setFilterState({}); setDefinition(EMPTY_DEFINITION); }, [setQuery, setFilterState]);
    const resolveTagName = useCallback(
        (id: string) => segmentFields?.tags.find((tag) => String(tag.id) === id)?.name ?? id,
        [segmentFields],
    );
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...facetChips(facets, filterState, setFilterState),
        ...definition.conditions
            .map((condition, index) => ({ condition, index }))
            .filter(({ condition }) => condition.type === 'predicate' || (condition.value ?? '').trim() !== '')
            .map(({ condition, index }) => ({
                id: `segment:${index}`,
                label: segmentConditionLabel(condition, tSeg, resolveTagName),
                onRemove: () => setDefinition({ ...definition, conditions: definition.conditions.filter((_, i) => i !== index) }),
            })),
    ];

    const [now] = useState(() => Date.now());
    const metricsByCompanyId = useMemo(() => {
        const map = new Map<number, CompanyMetrics>();
        for (const company of companies) {
            const persons = allContacts.filter((c) => c.companyId === company.id);
            const deals = allDeals.filter((d) => d.company === company.id);
            const personIds = new Set(persons.map((c) => c.id));
            const dealIds = new Set(deals.map((d) => d.id));
            const tasks = allTasks.filter((t) =>
                (t.personId != null && personIds.has(t.personId)) ||
                (t.dealId != null && dealIds.has(t.dealId)),
            );
            const activities = allActivities.filter((a) =>
                (a.personId != null && personIds.has(a.personId)) ||
                (a.dealId != null && dealIds.has(a.dealId)),
            );
            const notes = allNotes.filter((n) =>
                (n.person != null && personIds.has(n.person)) ||
                (n.deal != null && dealIds.has(n.deal)),
            );
            const userIds = new Set<number>();
            for (const t of tasks) if (t.assignedToId != null) userIds.add(t.assignedToId);
            for (const a of activities) userIds.add(a.createdById);
            for (const n of notes) userIds.add(n.author);

            const WEEK_MS = 7 * 24 * 60 * 60 * 1000;
            const firstWeekStart = now - 11 * WEEK_MS;
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

            const currency = pickDominantCurrency(deals);
            let pastRevenue = 0;
            let projectedRevenue = 0;
            for (const d of deals) {
                if ((d.currency || 'USD') !== currency) continue;
                if (isDealClosed(d)) {
                    pastRevenue += d.value ?? 0;
                } else {
                    projectedRevenue += d.value ?? 0;
                }
            }

            map.set(company.id, {
                persons,
                relatedUsers: allUsers.filter((u) => userIds.has(u.id)),
                pastRevenue,
                projectedRevenue,
                currency,
                numDeals: deals.length,
                numTasks: tasks.length,
                numActivities: activities.length,
                numNotes: notes.length,
                weeklyEngagement,
            });
        }
        return map;
    }, [now, companies, allContacts, allDeals, allTasks, allActivities, allNotes, allUsers]);

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

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <div className="flex items-center justify-between">
                        <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                        <div className="flex items-center gap-2">
                            <RecordsActions
                                entity="companies"
                                onNew={() => setNewDialogOpen(true)}
                                newLabel={t('new')}
                                newAriaLabel={t('addCompanyAriaLabel')}
                                onImported={refresh}
                                exportIds={companies.map((c) => c.id)}
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
                                <SegmentBuilder definition={definition} fields={segmentFields} onChange={setDefinition} />
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
                        <RecordsFilterPills<Company>
                            facets={facets}
                            filterState={filterState}
                            onChange={setFilterState}
                        />
                    </FilterBar>
                </Rise>

                {(() => {
                    const pageFullySelected = companies.length > 0 && selectedCompanyIds.length >= companies.length;
                    const allMatchingSelected = total > companies.length && selectedCompanyIds.length >= total;
                    const canSelectAllMatching = hasActiveFilters && pageFullySelected && total > companies.length && selectedCompanyIds.length < total;
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
                        loading={loading || segmentsLoading}
                        columns={[...columns, ...customColumns.map((c) => ({ ...c, sortable: false }))]}
                        addColumnSlot={addColumnSlot}
                        renderCard={(item, { onQuickEdit, onDelete }) => (
                            <CompanyCard
                                company={item}
                                metrics={metricsByCompanyId.get(item.id)}
                                metricsStatus={metricsStatus}
                                onFirstExpand={ensureMetricsLoaded}
                                onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                onDelete={onDelete ? () => onDelete(item) : undefined}
                            />
                        )}
                        renderAvatar={(item) => <CompanyAvatar company={item} />}
                        detailPath={(item) => `/records/companies/${item.id}`}
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
            </div>
        </div>
    );
}
