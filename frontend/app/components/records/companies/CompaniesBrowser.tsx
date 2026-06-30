'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { Button } from '@/components/ui/button';
import RecordsImportExport from '@/app/components/import/RecordsImportExport';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { PlusIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    Squares2X2Icon,
    TableCellsIcon,
    TagIcon,
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import SegmentBuilder, { EMPTY_DEFINITION, isSegmentDefinition, segmentConditionLabel } from '@/app/components/records/SegmentBuilder';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, SegmentedToggle, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useRecordsSort } from '@/app/hooks/useRecordsSort';
import { type ColumnDef, applyRecordFilters, deriveFilterOptions, facetChips, countActiveFilters } from '@/app/components/records/types';
import CompanyCard from '@/app/components/records/companies/CompanyCard';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { createCompany, getUsers, getTasks, getDeals, updateCompany, getActivities, getNotes, getCompanyTemperatures, isFieldError, evaluateSegments, getSegmentFields, getTags, bulkAddTagToCompanies, bulkRemoveTagFromCompanies, bulkDeleteCompanies } from '@/app/lib/api';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { uploadCompanyLogo, pickDominantCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { type Company, type CreateCompanyPayload, type UpdateCompanyPayload, type Contact, type Activity, type Note, type Task, type User, type Deal, type CompanyMetrics, type LoadStatus, type RelationshipTemperature, type SavedView, type SavedViewConfig, type SegmentDefinition, type SegmentFields, type Tag } from '@/app/lib/types';
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

export default function CompaniesBrowser({ companies, savedViews }: { companies: Company[]; savedViews: SavedView[] }) {
    const router = useRouter();
    const t = useTranslations('CompaniesBrowser');
    const tf = useTranslations('Filters');
    const tSeg = useTranslations('SmartSegments');
    const reduce = useReducedMotion() ?? false;
    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredCompanies,
        selectedItems: selectedCompanies,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Company>({
        items: companies,
        storageKey: 'companies:view',
        searchFields,
    });
    const { sortKey, sortDirection, onSortChange, applySort, sortState } = useRecordsSort();

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

    const [allContacts, setAllContacts] = useState<Contact[]>([]);
    const [allDeals, setAllDeals] = useState<Deal[]>([]);
    const [allTasks, setAllTasks] = useState<Task[]>([]);
    const [allActivities, setAllActivities] = useState<Activity[]>([]);
    const [allNotes, setAllNotes] = useState<Note[]>([]);
    const [allUsers, setAllUsers] = useState<User[]>([]);
    const [metricsStatus, setMetricsStatus] = useState<LoadStatus>('idle');

    const [tempByCompanyId, setTempByCompanyId] = useState<Map<number, RelationshipTemperature>>(new Map());
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
    }, []);
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
    useEffect(() => {
        getCompanyTemperatures()
            .then((temps) => setTempByCompanyId(new Map(temps.map((temp) => [temp.id, temp]))))
            .catch(() => setTempByCompanyId(new Map()));
    }, []);

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
            .catch((err) => {
                console.error(err);
                setMetricsStatus('error');
                toastError(t('toastMetricsLoadFailed'));
            });
    }, [metricsStatus, t]);

    const closeNewDialog = (open: boolean) => {
        setNewDialogOpen(open);
        if (!open) {
            setNewPayload(emptyDraft);
            setLogoFile(null);
            setCreationSucceeded(false);
        }
    };

    const createNewCompany = async () => {
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const created = await createCompany(newPayload);
            if (logoFile) {
                const logoUrl = await uploadCompanyLogo(created.id, logoFile);
                await updateCompany(created.id, { ...newPayload, logoUrl });
            }
            toastSuccess(t('toastCompanyCreated'));
            setIsCreating(false);
            setCreationSucceeded(true);
            setTimeout(() => {
                closeNewDialog(false);
                router.refresh();
            }, 850);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            console.error(err);
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
            router.refresh();
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

    const selectedCompanyIds = useMemo(() => selectedCompanies.map((c) => c.id), [selectedCompanies]);

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
                router.refresh();
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
    const onBulkTagSuccess = useCallback(() => { setSelectedIds(new Set()); router.refresh(); }, [setSelectedIds, router]);

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
            filter: { getValue: (c) => c.industry ?? null, emptyLabel: t('filterNoIndustry') },
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

    const activeSegmentIds = evaluable.conditions.length > 0 && segmentResult?.key === segmentsKey ? segmentResult.ids : null;
    const segmentsLoading = evaluable.conditions.length > 0 && segmentResult?.key !== segmentsKey;
    const visibleCompanies = useMemo(
        () => {
            const filtered = applyRecordFilters(filteredCompanies, columns, filterState);
            return activeSegmentIds ? filtered.filter((company) => activeSegmentIds.has(company.id)) : filtered;
        },
        [filteredCompanies, columns, filterState, activeSegmentIds],
    );

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('company', visibleCompanies);

    const facets = useMemo(() => deriveFilterOptions(columns, filteredCompanies), [columns, filteredCompanies]);
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

    // TODO: move processing to the backend so the frontend doesn't traverse the full tables to derive per-company metrics

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
            const now = Date.now();
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
    }, [companies, allContacts, allDeals, allTasks, allActivities, allNotes, allUsers]);

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
        () => ({ filters: filterState, query, sortKey, sortDirection, segments: definition }),
        [filterState, query, sortKey, sortDirection, definition],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            setFilterState(config.filters ?? {});
            setQuery(config.query ?? '');
            applySort(config.sortKey ?? null, config.sortDirection ?? 'asc');
            setDefinition(isSegmentDefinition(config.segments) ? config.segments : EMPTY_DEFINITION);
        },
        [setFilterState, setQuery, applySort],
    );

    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <div className="flex items-center gap-2">
                    <RecordsImportExport entity="companies" onImported={() => router.refresh()} exportIds={visibleCompanies.map((c) => c.id)} />
                    <Button className="bg-brand text-white" aria-label={t('addCompanyAriaLabel')} onClick={() => setNewDialogOpen(true)}>
                        <PlusIcon strokeWidth={2.5} />
                        {t('new')}
                    </Button>
                </div>
            </div>

            <SavedViewsBar
                recordType="company"
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

            <RecordsRenderView<Company>
                data={visibleCompanies}
                loading={segmentsLoading}
                columns={[...columns, ...customColumns]}
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
                onSelectedIdsChange={setSelectedIds}
                onQuickEdit={quickEditOne}
                onDelete={deleteOne}
                gridClassName="grid grid-cols-1 gap-3"
                entityLabel={t('entityLabel')}
                selectionActions={selectionActions}
                sortState={sortState}
            />

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
    );
}