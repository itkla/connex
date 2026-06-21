'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { PlusIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TableCellsIcon,
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterMenu from '@/app/components/records/RecordsFilterMenu';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useRecordsSort } from '@/app/hooks/useRecordsSort';
import { type ColumnDef, applyRecordFilters } from '@/app/components/records/types';
import CompanyCard from '@/app/components/records/companies/CompanyCard';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import NewCompanyDialog from '@/app/components/records/companies/NewCompanyDialog';
import QuickEditCompanySheet, { type CompanyDraft } from '@/app/components/records/companies/QuickEditCompanySheet';
import { createCompany, deleteCompany, getUsers, getTasks, getDeals, updateCompany, getActivities, getNotes, isFieldError } from '@/app/lib/api';
import { uploadCompanyLogo, pickDominantCurrency, parseMysqlDateTime } from '@/app/lib/utils';
import { type Company, type CreateCompanyPayload, type UpdateCompanyPayload, type Contact, type Activity, type Note, type Task, type User, type Deal, type CompanyMetrics, type LoadStatus } from '@/app/lib/types';
import { getContacts } from '@/app/lib/api';

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

export default function CompaniesBrowser({ companies }: { companies: Company[] }) {
    const router = useRouter();
    const t = useTranslations('CompaniesBrowser');
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
    const { sortKey, sortDirection, onSortChange, sortState } = useRecordsSort();

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

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(Array.from(selectedIds).map((id) => deleteCompany(Number(id))));
            toastSuccess(
                selectedIds.size === 1 ? t('toastCompanyDeleted') : t('toastCompaniesDeleted', { count: selectedIds.size }),
            );
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastDeleteFailed'));
        } finally {
            setIsDeleting(false);
        }
    };

    const viewSelected = () => {
        if (selectedCompanies.length === 1) {
            router.push(`/records/companies/${selectedCompanies[0].id}`);
        } else {
            selectedCompanies.forEach((c) => window.open(`/records/companies/${c.id}`, '_blank'));
        }
    };

    const columns: ColumnDef<Company>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (c) => c.name ?? null },
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
    ], [t]);

    const visibleCompanies = useMemo(
        () => applyRecordFilters(filteredCompanies, columns, filterState),
        [filteredCompanies, columns, filterState],
    );

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
                const closed = d.closedAt ? Date.parse(d.closedAt) : NaN;
                if (Number.isFinite(closed) && closed <= now) {
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
                    <DropdownMenuItem variant="destructive" onSelect={(e) => { e.preventDefault(); setDeleteDialogOpen(true); }}>
                        <TrashIcon />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </ButtonGroup>
    );

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <Button className="bg-brand text-white" aria-label={t('addCompanyAriaLabel')} onClick={() => setNewDialogOpen(true)}>
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <div className="flex items-center gap-4">
                <RecordsFilterMenu<Company>
                    columns={columns}
                    items={filteredCompanies}
                    filterState={filterState}
                    onChange={setFilterState}
                />
                {displayMode === 'grid' && (
                    <RecordsSortMenu
                        columns={columns}
                        sortKey={sortKey}
                        sortDirection={sortDirection}
                        onSortChange={onSortChange}
                    />
                )}
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
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'grid' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
                    >
                        <Squares2X2Icon className="size-4" />
                    </button>
                    <button
                        type="button"
                        onClick={() => setDisplayMode('table')}
                        aria-label={t('tableViewAriaLabel')}
                        aria-pressed={displayMode === 'table'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'table' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
                    >
                        <TableCellsIcon className="size-4" />
                    </button>
                </div>

                <div className="relative ml-auto w-full max-w-sm">
                    <input
                        type="text"
                        placeholder={t('searchPlaceholder')}
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        className="w-full rounded-full bg-muted px-4 py-2 pr-10 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand"
                    />
                    <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                </div>
            </div>

            <RecordsRenderView<Company>
                data={visibleCompanies}
                columns={columns}
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
        </div>
    );
}