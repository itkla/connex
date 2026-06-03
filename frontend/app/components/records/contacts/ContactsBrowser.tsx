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
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TableCellsIcon,
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import RecordsFilterMenu from '@/app/components/records/RecordsFilterMenu';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { type ColumnDef, applyRecordFilters } from '@/app/components/records/types';
import ContactCard from '@/app/components/records/contacts/ContactCard';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import { deleteContact, updateContact, createContact, getCompanies } from '@/app/lib/api';
import { uploadContactPicture } from '@/app/lib/utils';
import { type Contact, type UpdateContactPayload, type Company, type CreateContactPayload } from '@/app/lib/types';

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

const searchFields = (c: Contact) => [c.name, c.email, c.phone, c.title];

export default function ContactsBrowser({ contacts }: { contacts: Contact[] }) {
    const router = useRouter();
    const t = useTranslations('ContactsBrowser');
    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredContacts,
        selectedItems: selectedContacts,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Contact>({
        items: contacts,
        storageKey: 'contacts:view',
        searchFields,
    });

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

    const emptyContactDraft: CreateContactPayload = { name: '', email: '', phone: '', title: '' };
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
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
        }
    };

    const createNewContact = async () => {
        setIsCreating(true);
        try {
            const newContact = await createContact(newContactPayload);
            if (imageFile) {
                const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                await updateContact(newContact.id, { ...newContactPayload, imageUrl });
            }
            toastSuccess(t('toastContactCreated'));
            setNewContactDialogOpen(false);
            router.refresh();
        } catch (err) {
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
            router.refresh();
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
            router.refresh();
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
            router.refresh();
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
        { key: 'name', label: t('columnName'), getSortValue: (c) => c.name ?? null },
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
            filter: { getValue: (c) => c.company?.name ?? null, emptyLabel: t('filterNoCompany') },
        },
        {
            key: 'title',
            label: t('columnTitle'),
            getSortValue: (c) => c.title ?? null,
            filter: { getValue: (c) => c.title ?? null },
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

    const visibleContacts = useMemo(
        () => applyRecordFilters(filteredContacts, columns, filterState),
        [filteredContacts, columns, filterState],
    );

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-neutral-100">
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

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('heading')}</h1>
                <Button className="bg-brand text-white" aria-label={t('newAria')} onClick={() => setNewContactDialogOpen(true)}>
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <div className="flex items-center gap-4">
                <RecordsFilterMenu<Contact>
                    columns={columns}
                    items={filteredContacts}
                    filterState={filterState}
                    onChange={setFilterState}
                />
                <div
                    role="group"
                    aria-label={t('displayModeAria')}
                    className="inline-flex rounded-full bg-neutral-100 p-0.5 ring-1 ring-black/5"
                >
                    <button
                        type="button"
                        onClick={() => setDisplayMode('grid')}
                        aria-label={t('gridViewAria')}
                        aria-pressed={displayMode === 'grid'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'grid' ? 'bg-white text-neutral-900 shadow' : 'text-neutral-500 hover:text-neutral-700'}`}
                    >
                        <Squares2X2Icon className="size-4" />
                    </button>
                    <button
                        type="button"
                        onClick={() => setDisplayMode('table')}
                        aria-label={t('tableViewAria')}
                        aria-pressed={displayMode === 'table'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'table' ? 'bg-white text-neutral-900 shadow' : 'text-neutral-500 hover:text-neutral-700'}`}
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
                        className="w-full rounded-full bg-neutral-100 px-4 py-2 pr-10 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
                    />
                    <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500" />
                </div>
            </div>

            <RecordsRenderView<Contact>
                data={visibleContacts}
                columns={columns}
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