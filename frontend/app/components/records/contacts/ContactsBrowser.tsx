'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { PlusIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import { BuildingOffice2Icon, NoSymbolIcon } from '@heroicons/react/24/outline';
import {
    Squares2X2Icon,
    TableCellsIcon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import { SearchField, FilterBar, SegmentedToggle, type FilterChipData } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import type { SavedView, SavedViewConfig } from '@/app/lib/types';
import { type ColumnDef, type ColumnFilterFacet, FILTER_EMPTY, facetChips, countActiveFilters } from '@/app/components/records/types';
import ContactCard from '@/app/components/records/contacts/ContactCard';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import { deleteContact, updateContact, createContact, getCompanies, getContactsPage, getContactTemperatures, getPersonFacets, isFieldError } from '@/app/lib/api';
import { uploadContactPicture } from '@/app/lib/utils';
import { type Contact, type UpdateContactPayload, type Company, type CreateContactPayload, type ContactsPageParams, type PersonFacets, type RelationshipTemperature } from '@/app/lib/types';
import TemperaturePill from '@/app/components/records/TemperaturePill';

const NO_ITEMS: Contact[] = [];
const searchFields = (c: Contact) => [c.name, c.email, c.phone, c.title];

function toDraft(c: Contact): ContactDraft {
    return {
        name: c.name ?? '',
        email: c.email ?? '',
        phone: c.phone ?? '',
        title: c.title ?? '',
    };
}

function diffDraft(original: ContactDraft, draft: ContactDraft): boolean {
    return (
        original.name !== draft.name ||
        original.email !== draft.email ||
        original.phone !== draft.phone ||
        original.title !== draft.title
    );
}

export default function ContactsBrowser({ savedViews }: { savedViews: SavedView[] }) {
    const router = useRouter();
    const t = useTranslations('ContactsBrowser');
    const tf = useTranslations('Filters');
    const reduce = useReducedMotion() ?? false;

    const {
        displayMode,
        setDisplayMode,
        // query,
        // setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        // filteredItems: filteredContacts,
        // selectedItems: selectedContacts,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Contact>({ items: NO_ITEMS, storageKey: 'contacts:view', searchFields });

    const filterParams = useMemo(() => {
        const company = filterState.company ?? [];
        const titles = filterState.title ?? [];
        const companies = company.filter((k) => k !== FILTER_EMPTY);
        const params: { companies?: string[]; titles?: string[]; noCompany?: boolean } = {};
        if (companies.length) params.companies = companies;
        if (titles.length) params.titles = titles;
        if (company.includes(FILTER_EMPTY)) params.noCompany = true;
        return params;
    }, [filterState]);

    const {
        items: contacts,
        total,
        loading,
        page,
        setPage,
        size,
        setSize,
        query,
        setQuery,
        sortKey,
        sortDirection,
        onSortChange,
        applySort,
        reload,
    } = useServerRecords<Contact, ContactsPageParams>(getContactsPage, filterParams);

    const selectedContacts = useMemo(() => contacts.filter((c) => selectedIds.has(c.id)), [contacts, selectedIds]);

    useEffect(() => { setSelectedIds(new Set()); }, [contacts, setSelectedIds]);

    const [personFacets, setPersonFacets] = useState<PersonFacets | null>(null);
    const loadFacets = useCallback(() => {
        getPersonFacets().then(setPersonFacets).catch(() => setPersonFacets(null));
    }, []);
    useEffect(() => { loadFacets(); }, [loadFacets]);

    const refresh = useCallback(() => { reload(); loadFacets(); }, [reload, loadFacets]);

    const facets = useMemo<ColumnFilterFacet[]>(() => {
        if (!personFacets) return [];
        const out: ColumnFilterFacet[] = [];
        const companyOptions = personFacets.companies.map((name) => ({ key: name, label: name }));
        if (personFacets.hasNoCompany) companyOptions.push({ key: FILTER_EMPTY, label: t('filterNoCompany') });
        if (companyOptions.length) out.push({ key: 'company', label: t('columnCompany'), options: companyOptions });
        const titleOptions = personFacets.titles.map((ti) => ({ key: ti, label: ti }));
        if (titleOptions.length) out.push({ key: 'title', label: t('columnTitle'), options: titleOptions });
        return out;
    }, [personFacets, t]);

    const hasActiveFilters = query.trim() !== '' || countActiveFilters(filterState) > 0;
    const clearAll = useCallback(() => { setQuery(''); setFilterState({}); }, [setQuery, setFilterState]);
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...facetChips(facets, filterState, setFilterState),
    ];

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, ContactDraft>>({});
    const [isSaving, setIsSaving] = useState(false);
    const [changeCompanyOpen, setChangeCompanyOpen] = useState(false);
    const [isClearingCompany, setIsClearingCompany] = useState(false);

    const [companies, setCompanies] = useState<Company[]>([]);
    useEffect(() => {
        getCompanies({}).then(setCompanies).catch(() => setCompanies([]));
    }, []);

    const [tempByContactId, setTempByContactId] = useState<Map<number, RelationshipTemperature>>(new Map());
    useEffect(() => {
        getContactTemperatures()
            .then((temps) => setTempByContactId(new Map(temps.map((temp) => [temp.id, temp]))))
            .catch(() => setTempByContactId(new Map()));
    }, []);

    const emptyContactDraft: CreateContactPayload = { name: '', email: '', phone: '', title: '' };
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>(emptyContactDraft);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const selectedCompany = useMemo(
        () => companies.find((c) => c.id === newContactPayload.companyId) ?? null,
        [companies, newContactPayload.companyId],
    );

    const closeNewContactDialog = (open: boolean) => {
        setNewContactDialogOpen(open);
        if (!open) {
            setNewContactPayload(emptyContactDraft);
            setImageFile(null);
            setCreationSucceeded(false);
        }
    };

    const createNewContact = async () => {
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const newContact = await createContact(newContactPayload);
            if (imageFile) {
                const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                await updateContact(newContact.id, { ...newContactPayload, imageUrl });
            }
            toastSuccess(t('toastContactCreated'));
            setIsCreating(false);
            setCreationSucceeded(true);
            setTimeout(() => {
                closeNewContactDialog(false);
                refresh();
            }, 900);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            console.error(err);
            toastError(t('toastFailedCreate'));
        } finally {
            setIsCreating(false);
        }
    };

    const openEditSheet = () => {
        const next: Record<number, ContactDraft> = {};
        for (const c of selectedContacts) next[c.id] = toDraft(c);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<ContactDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedContacts.filter((c) => {
            const draft = drafts[c.id];
            return draft && diffDraft(toDraft(c), draft);
        });

        if (changed.length === 0) {
            toast.info(t('toastNoChangesToSave'));
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((c) => !drafts[c.id].name.trim());
        if (invalid) {
            toast.error(t('toastNameRequiredForX', { name: invalid.name }));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((c) => {
                    const d = drafts[c.id];
                    const payload: UpdateContactPayload = {
                        name: d.name.trim(),
                        email: d.email.trim() || undefined,
                        phone: d.phone.trim() || undefined,
                        title: d.title.trim() || undefined,
                        companyId: c.companyId ?? c.company?.id ?? null,
                        imageUrl: c.imageUrl || undefined,
                    };
                    return updateContact(c.id, payload);
                }),
            );
            toastSuccess(
                changed.length === 1 ? t('toastContactUpdated') : t('toastContactsUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const bulkRemoveFromCompany = async () => {
        const affected = selectedContacts.filter((c) => c.companyId || c.company);
        if (affected.length === 0) {
            toast.info(t('toastNoneHaveCompany'));
            return;
        }
        setIsClearingCompany(true);
        try {
            await Promise.all(affected.map((c) => updateContact(c.id, {
                name: c.name,
                email: c.email || undefined,
                phone: c.phone || undefined,
                title: c.title || undefined,
                imageUrl: c.imageUrl || undefined,
                companyId: null,
            })));
            toastSuccess(
                affected.length === 1
                    ? t('toastRemovedFromCompany')
                    : t('toastRemovedNContactsFromCompanies', { count: affected.length }),
            );
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedRemoveFromCompany'));
        } finally {
            setIsClearingCompany(false);
        }
    };

    const quickEditOne = useCallback((contact: Contact) => {
        setSelectedIds(new Set([contact.id]));
        setDrafts({ [contact.id]: toDraft(contact) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((contact: Contact) => {
        setSelectedIds(new Set([contact.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(Array.from(selectedIds).map((id) => deleteContact(Number(id))));
            toastSuccess(
                selectedIds.size === 1 ? t('toastContactDeleted') : t('toastContactsDeleted', { count: selectedIds.size }),
            );
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const viewSelected = () => {
        if (selectedContacts.length === 1) {
            router.push(`/records/contacts/${selectedContacts[0].id}`);
        } else {
            selectedContacts.forEach((c) => window.open(`/records/contacts/${c.id}`, '_blank'));
        }
    };

    const columns: ColumnDef<Contact>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (c) => c.name ?? null, widthClass: 'min-w-48' },
        {
            key: 'warmth',
            label: t('columnWarmth'),
            getSortValue: (c) => tempByContactId.get(c.id)?.score ?? null,
            render: (c) => <TemperaturePill temp={tempByContactId.get(c.id)} />,
        },
        {
            key: 'email',
            label: t('columnEmail'),
            getSortValue: (c) => c.email ?? null,
            copyable: { label: t('copyableEmail'), getValue: (c) => c.email },
        },
        {
            key: 'phone',
            label: t('columnPhone'),
            getSortValue: (c) => c.phone ?? null,
            copyable: { label: t('copyablePhone'), getValue: (c) => c.phone },
        },
        {
            key: 'company',
            label: t('columnCompany'),
            getSortValue: (c) => c.company?.name ?? null,
            render: (c) => c.company?.name,
            copyable: { label: t('copyableCompany'), getValue: (c) => c.company?.name ?? '' },
            // filter: { getValue: (c) => c.company?.name ?? null, emptyLabel: t('filterNoCompany') },
        },
        {
            key: 'title',
            label: t('columnTitle'),
            getSortValue: (c) => c.title ?? null,
            // filter: { getValue: (c) => c.title ?? null },
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
    ], [t, tempByContactId]);

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('person', contacts);

    // const visibleContacts = useMemo(
    //     () => applyRecordFilters(filteredContacts, columns, filterState),
    //     [filteredContacts, columns, filterState],
    // );

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
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setChangeCompanyOpen(true); }}>
                        <BuildingOffice2Icon />
                        {t('changeCompany')}
                    </DropdownMenuItem>
                    <DropdownMenuItem
                        disabled={isClearingCompany}
                        onSelect={(e) => { e.preventDefault(); bulkRemoveFromCompany(); }}
                    >
                        <NoSymbolIcon />
                        {t('removeFromCompany')}
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
        () => ({ filters: filterState, query, displayMode, sortKey, sortDirection }),
        [filterState, query, displayMode, sortKey, sortDirection],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            setFilterState(config.filters ?? {});
            setQuery(config.query ?? '');
            setDisplayMode(config.displayMode ?? 'table');
            applySort(config.sortKey ?? null, config.sortDirection ?? 'asc');
        },
        [setFilterState, setQuery, setDisplayMode, applySort],
    );

    return (
        <div className="page-grid gap-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('heading')}</h1>
                <Button className="bg-brand text-white" aria-label={t('newAria')} onClick={() => setNewContactDialogOpen(true)}>
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <SavedViewsBar
                recordType="person"
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
                        {displayMode === 'grid' && (
                            <RecordsSortMenu
                                columns={columns}
                                sortKey={sortKey}
                                sortDirection={sortDirection}
                                onSortChange={onSortChange}
                            />
                        )}
                        <SegmentedToggle
                            ariaLabel={t('displayModeAria')}
                            value={displayMode}
                            onChange={setDisplayMode}
                            options={[
                                { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('gridViewAria') },
                                { value: 'table', icon: <TableCellsIcon className="size-4" />, ariaLabel: t('tableViewAria') },
                            ]}
                        />
                    </div>
                }
            >
                <RecordsFilterPills<Contact>
                    facets={facets}
                    filterState={filterState}
                    onChange={setFilterState}
                />
            </FilterBar>

            <RecordsRenderView<Contact>
                data={contacts}
                columns={[...columns, ...customColumns]}
                addColumnSlot={addColumnSlot}
                renderCard={(item, { onQuickEdit, onDelete }) => (
                    <ContactCard
                        id={item.id}
                        name={item.name}
                        title={item.title}
                        company={item.company?.name}
                        email={item.email}
                        phone={item.phone}
                        imageUrl={item.imageUrl}
                        onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                        onDelete={onDelete ? () => onDelete(item) : undefined}
                    />
                )}
                renderAvatar={(item) => <ContactAvatar contact={item} />}
                detailPath={(item) => `/records/contacts/${item.id}`}
                displayMode={displayMode}
                selectedIds={selectedIds}
                onSelectedIdsChange={setSelectedIds}
                onQuickEdit={quickEditOne}
                onDelete={deleteOne}
                entityLabel="contact"
                selectionActions={selectionActions}
                loading={loading}
                pagination={{ page, pageSize: size, total, onPageChange: setPage, onPageSizeChange: setSize }}
                sortState={{ key: sortKey, direction: sortDirection, onSortChange }}
            />

            <QuickEditSheet
                editSheetOpen={editSheetOpen}
                setEditSheetOpen={setEditSheetOpen}
                selectedIds={selectedIds}
                selectedContacts={selectedContacts}
                drafts={drafts}
                updateDraft={updateDraft}
                isSaving={isSaving}
                saveEdits={saveEdits}
            />

            <NewContactDialog
                newContactDialogOpen={newContactDialogOpen}
                setNewContactDialogOpen={closeNewContactDialog}
                newContactPayload={newContactPayload}
                setNewContactPayload={setNewContactPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                companies={companies}
                selectedCompany={selectedCompany}
                isCreating={isCreating}
                isSuccess={creationSucceeded}
                createNewContact={createNewContact}
            />

            <DeleteRecordDialog
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedContacts}
                entityLabel="contact"
                getDisplayName={(c) => c.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />

            <ChangeCompanyDialog
                open={changeCompanyOpen}
                onOpenChange={setChangeCompanyOpen}
                contacts={selectedContacts}
                companies={companies}
            />
        </div>
    );
}