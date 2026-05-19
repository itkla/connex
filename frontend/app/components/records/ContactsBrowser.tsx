'use client';

import { useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { PlusIcon, FunnelIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TableCellsIcon,
    ChevronDownIcon,
} from '@heroicons/react/24/outline';
import DataRenderView, { type SelectionId } from '@/app/components/records/DataRenderView';
import { deleteContact, updateContact, createContact, getCompanies } from '@/app/lib/api';
import { type Contact, type UpdateContactPayload, type Company, type CreateContactPayload } from '@/app/lib/types';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import DeleteContactDialog from '@/app/components/records/contacts/DeleteContactDialog';
import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';

function toDraft(c: Contact): ContactDraft {
    return {
        name: c.name ?? '',
        email: c.email ?? '',
        phone: c.phone ?? '',
        title: c.title ?? '',
        // expand if 
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

type DisplayMode = 'grid' | 'table';

const VIEW_STORAGE_KEY = 'contacts:view';

// display mode for  the contacts browser
function isDisplayMode(value: unknown): value is DisplayMode {
    return value === 'grid' || value === 'table';
}

// TODO: remove the useEffects to make the page load better. for now tho keep it cuz it shows the page actually works
export default function ContactsBrowser({ contacts }: { contacts: Contact[] }) {
    const router = useRouter();
    const pathname = usePathname();
    const searchParams = useSearchParams();

    const urlView = searchParams.get('view');
    const [displayMode, setDisplayMode] = useState<DisplayMode>(
        isDisplayMode(urlView) ? urlView : 'table',
    );
    const [initialized, setInitialized] = useState(false);
    const [query, setQuery] = useState('');
    const [selectedIds, setSelectedIds] = useState<Set<SelectionId>>(new Set());
    const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);


    useEffect(() => {
        if (!urlView) {
            const stored = window.localStorage.getItem(VIEW_STORAGE_KEY);
            if (isDisplayMode(stored)) setDisplayMode(stored);
        }
        setInitialized(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (!initialized) return;
        window.localStorage.setItem(VIEW_STORAGE_KEY, displayMode);
        const params = new URLSearchParams(searchParams.toString());
        if (params.get('view') === displayMode) return;
        params.set('view', displayMode);
        router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    }, [displayMode, initialized, pathname, router, searchParams]);

    const filteredContacts = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return contacts;
        return contacts.filter((c) =>
            [c.name, c.email, c.phone, c.title]
                .some((field) => field?.toLowerCase().includes(q))
        );
    }, [contacts, query]);

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, ContactDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const selectedContacts = useMemo(
        () => contacts.filter((c) => selectedIds.has(c.id)),
        [contacts, selectedIds],
    );

    const [companies, setCompanies] = useState<Company[]>([]);
    useEffect(() => {
        getCompanies({}).then(setCompanies).catch(() => setCompanies([]));
    }, []);

    // new contact dialog form
    const emptyContactDraft: CreateContactPayload = { name: '', email: '', phone: '', title: '' };
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>(emptyContactDraft);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const selectedCompany = useMemo(
        () => companies.find((c) => c.id === newContactPayload.companyId) ?? null,
        [companies, newContactPayload.companyId],
    );

    useEffect(() => {
        if (!newContactDialogOpen) {
            setNewContactPayload(emptyContactDraft);
            setImageFile(null);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [newContactDialogOpen]);

    async function uploadContactPicture(contactId: number, file: File): Promise<string> {
        const formData = new FormData();
        formData.append('contactPicture', file);
        const res = await fetch(`/api/contacts/profile-picture?contactId=${contactId}`, {
            method: 'PUT',
            body: formData,
        });
        if (!res.ok) {
            throw new Error('Failed to upload contact picture');
        }
        const data = (await res.json()) as { imageUrl: string };
        return data.imageUrl;
    }

    const createNewContact = async () => {
        setIsCreating(true);
        try {
            const newContact = await createContact(newContactPayload);
            if (imageFile) {
                const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                await updateContact(newContact.id, { ...newContactPayload, imageUrl });
            }
            toast.success('Contact created', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            setNewContactDialogOpen(false);
            router.refresh();
        } catch (err) {
            console.error(err instanceof Error);
            toast.error('Failed to create contact', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsCreating(false);
        }
    };

    // quick edit sheet
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
            toast.info('No changes to save');
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((c) => !drafts[c.id].name.trim());
        if (invalid) {
            toast.error(`Name is required for "${invalid.name}"`);
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
                    };
                    return updateContact(c.id, payload);
                }),
            );
            toast.success(
                changed.length === 1 ? 'Contact updated' : `${changed.length} contacts updated`,
                {
                    style: { backgroundColor: 'var(--color-brand)', color: 'white' },
                },
            );
            setEditSheetOpen(false);
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to save', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOneContact = (contact: Contact) => {
        setSelectedIds(new Set([contact.id]));
        setDrafts({ [contact.id]: toDraft(contact) });
        setEditSheetOpen(true);
    };

    const deleteOneContact = (contact: Contact) => {
        setSelectedIds(new Set([contact.id]));
        setDeleteDialogOpen(true);
    };

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(
                Array.from(selectedIds).map((id) => deleteContact(Number(id))),
            );
            toast.success(
                selectedIds.size === 1
                    ? 'Contact deleted'
                    : `${selectedIds.size} contacts deleted`,
                {
                    style: {
                        backgroundColor: "var(--color-brand)",
                        color: "white",
                    }
                }
            );
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to delete', {
                style: {
                    backgroundColor: "var(--color-destructive)",
                    color: "white",
                }
            });
        } finally {
            setIsDeleting(false);
        }
    };

    const openContactPage = (contact: Contact[]) => {
        // use global selectedContacts state for now.
        if (selectedContacts.length === 1) {
            router.push(`/records/contacts/${selectedContacts[0].id}`);
        } else {
            selectedContacts.forEach((contact) => {
                window.open(`/records/contacts/${contact.id}`, '_blank');
            });
        }
    }

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">Contacts</h1>
                <Button className="bg-brand text-white" aria-label="Add contact" onClick={() => setNewContactDialogOpen(true)} >
                    <PlusIcon className="" strokeWidth={2.5} />
                    New
                </Button>
            </div>

            <div className="flex items-center gap-4">
                <button
                    type="button"
                    className="flex items-center gap-2 rounded-full bg-neutral-100 px-4 py-2 text-sm text-neutral-700 ring-1 ring-black/5 transition hover:bg-neutral-200"
                >
                    <FunnelIcon className="size-4 text-neutral-500" />

                    <ChevronDownIcon className="size-4 text-neutral-500" />
                </button>
                <div
                    role="group"
                    aria-label="Display mode"
                    className="inline-flex rounded-full bg-neutral-100 p-0.5 ring-1 ring-black/5"
                >
                    <button
                        type="button"
                        onClick={() => setDisplayMode('grid')}
                        aria-label="Grid view"
                        aria-pressed={displayMode === 'grid'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'grid'
                            ? 'bg-white text-neutral-900 shadow'
                            : 'text-neutral-500 hover:text-neutral-700'
                            }`}
                    >
                        <Squares2X2Icon className="size-4" />
                    </button>
                    <button
                        type="button"
                        onClick={() => setDisplayMode('table')}
                        aria-label="Table view"
                        aria-pressed={displayMode === 'table'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'table'
                            ? 'bg-white text-neutral-900 shadow'
                            : 'text-neutral-500 hover:text-neutral-700'
                            }`}
                    >
                        <TableCellsIcon className="size-4" />
                    </button>
                </div>

                {/* if any checkbox is checked, show the selected count AND selection tools (e.g. delete, edit, view) */}
                {selectedIds.size > 0 && (
                    <div className="flex items-center gap-2">
                        <span className="text-sm text-neutral-500">
                            {selectedIds.size} selected
                        </span>
                        <ButtonGroup className="rounded-full bg-neutral-100">
                            <Button variant="outline" size="sm" onClick={() => openContactPage(selectedContacts)}>
                                <EyeIcon className="size-4" />
                                View
                            </Button>
                            <Button variant="outline" size="sm" onClick={openEditSheet}>
                                <PencilIcon className="size-4" />
                                Quick edit
                            </Button>
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <Button variant="outline" size="sm">
                                        <EllipsisVerticalIcon className="size-4" />
                                    </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent>
                                    <DropdownMenuItem
                                        className="text-destructive hover:bg-red-500/10 hover:bg-destructive"
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            setDeleteDialogOpen(true);
                                        }}
                                    >
                                        <TrashIcon className="size-4 text-destructive hover:text-destructive" />
                                        Delete
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </ButtonGroup>
                    </div>

                )}

                <div className="relative ml-auto w-full max-w-sm">
                    <input
                        type="text"
                        placeholder="Search contacts"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        className="w-full rounded-full bg-neutral-100 px-4 py-2 pr-10 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
                    />
                    <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500" />
                </div>
            </div>

            <DataRenderView
                data={filteredContacts}
                displayMode={displayMode}
                selectedIds={selectedIds}
                onSelectedIdsChange={setSelectedIds}
                onQuickEditContact={quickEditOneContact}
                onDeleteContact={deleteOneContact}
            />

            {/* moved quick edit sheet to contacts/QuickEditSheet.tsx */}
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
            {/* moved contact creation dialog to contacts/NewContactDialog.tsx */}
            <NewContactDialog
                newContactDialogOpen={newContactDialogOpen}
                setNewContactDialogOpen={setNewContactDialogOpen}
                newContactPayload={newContactPayload}
                setNewContactPayload={setNewContactPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                companies={companies}
                selectedCompany={selectedCompany}
                isCreating={isCreating}
                createNewContact={createNewContact}
            />

            {/* moved delete dialog to contacts/DeleteContactDialog.tsx */}
            <DeleteContactDialog
                deleteDialogOpen={deleteDialogOpen}
                setDeleteDialogOpen={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedContacts={selectedContacts}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}
