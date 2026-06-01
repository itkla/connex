'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { PlusIcon, EllipsisVerticalIcon, PencilIcon, TrashIcon, FunnelIcon } from '@heroicons/react/24/solid';
import {
    MagnifyingGlassIcon,
    ChevronDownIcon,
    Squares2X2Icon,
    TableCellsIcon,
    UserIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { type ColumnDef } from '@/app/components/records/types';
import NoteCard from '@/app/components/activity/notes/NoteCard';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';
import QuickEditNoteSheet from '@/app/components/activity/notes/QuickEditNoteSheet';
import { deleteNote, updateNote } from '@/app/lib/api';
import { formatDate, formatDateTime } from '@/app/lib/utils';
import type { Contact, Deal, Note, NoteDraft, UpdateNotePayload, User } from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';

type Props = {
    notes: Note[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
};

function toDraft(n: Note): NoteDraft {
    return {
        content: n.content ?? '',
        author: n.author,
        person: n.person ?? null,
        deal: n.deal ?? null,
    };
}

function diffDraft(original: NoteDraft, draft: NoteDraft): boolean {
    return (
        original.content !== draft.content ||
        (original.person ?? null) !== (draft.person ?? null) ||
        (original.deal ?? null) !== (draft.deal ?? null)
    );
}

export default function NotesBrowser({ notes, persons, deals, users, currentUserId }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityNotes');
    const locale = useLocale();

    const personById = useMemo(() => {
        const map = new Map<number, Contact>();
        for (const p of persons) map.set(p.id, p);
        return map;
    }, [persons]);

    const dealById = useMemo(() => {
        const map = new Map<number, Deal>();
        for (const d of deals) map.set(d.id, d);
        return map;
    }, [deals]);

    const userById = useMemo(() => {
        const map = new Map<number, User>();
        for (const u of users) map.set(u.id, u);
        return map;
    }, [users]);

    const searchFields = useCallback(
        (n: Note) => [
            n.content,
            n.person ? personById.get(n.person)?.name : null,
            n.deal ? dealById.get(n.deal)?.name : null,
            userById.get(n.author)?.displayName,
            userById.get(n.author)?.username,
        ],
        [personById, dealById, userById],
    );

    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredNotes,
        selectedItems: selectedNotes,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Note>({
        items: notes,
        storageKey: 'notes:view',
        searchFields,
        initialDisplayMode: 'grid',
    });

    const [editingNote, setEditingNote] = useState<Note | null>(null);
    const [creating, setCreating] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, NoteDraft>>({});
    const [isSaving, setIsSaving] = useState(false);
    const [groupBy, setGroupBy] = useState<'none' | 'person' | 'company'>('none');

    const groups = useMemo(() => {
        if (groupBy === 'none' || displayMode !== 'grid') {
            return [{ id: '__all', label: '', notes: filteredNotes }];
        }
        const map = new Map<string, { id: string; label: string; sortKey: string; notes: Note[] }>();
        const unlinkedKey = '__unlinked';
        for (const n of filteredNotes) {
            let key: string;
            let label: string;
            if (groupBy === 'person') {
                const person = n.person ? personById.get(n.person) : null;
                if (person) {
                    key = `person-${person.id}`;
                    label = person.name;
                } else {
                    key = unlinkedKey;
                    label = t('groupUnlinkedPerson');
                }
            } else {
                const person = n.person ? personById.get(n.person) : null;
                const company = person?.company ?? null;
                if (company) {
                    key = `company-${company.id}`;
                    label = company.name;
                } else {
                    key = unlinkedKey;
                    label = t('groupUnlinkedCompany');
                }
            }
            if (!map.has(key)) {
                map.set(key, {
                    id: key,
                    label,
                    sortKey: key === unlinkedKey ? '￿' : label.toLowerCase(),
                    notes: [],
                });
            }
            map.get(key)!.notes.push(n);
        }
        return Array.from(map.values()).sort((a, b) => a.sortKey.localeCompare(b.sortKey));
    }, [filteredNotes, groupBy, displayMode, personById, t]);

    const closeNoteDialog = (open: boolean) => {
        if (!open) {
            setEditingNote(null);
            setCreating(false);
        }
    };

    const openEdit = useCallback((note: Note) => {
        setEditingNote(note);
    }, []);

    const requestDelete = useCallback(
        (note: Note) => {
            setSelectedIds(new Set([note.id]));
            setDeleteDialogOpen(true);
        },
        [setSelectedIds, setDeleteDialogOpen],
    );

    const openEditSheet = () => {
        const next: Record<number, NoteDraft> = {};
        for (const n of selectedNotes) next[n.id] = toDraft(n);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<NoteDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedNotes.filter((n) => {
            const draft = drafts[n.id];
            return draft && diffDraft(toDraft(n), draft);
        });

        if (changed.length === 0) {
            toast.info(t('toastNoChangesToSave'));
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((n) => !drafts[n.id].content.trim());
        if (invalid) {
            toastError(t('toastContentRequired'));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((n) => {
                    const d = drafts[n.id];
                    const payload: UpdateNotePayload = {
                        content: d.content.trim(),
                        author: n.author,
                        person: d.person ?? null,
                        deal: d.deal ?? null,
                    };
                    return updateNote(n.id, payload);
                }),
            );
            toastSuccess(
                changed.length === 1
                    ? t('toastNoteUpdated')
                    : t('toastNotesUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(Array.from(selectedIds).map((id) => deleteNote(Number(id))));
            toastSuccess(
                selectedIds.size === 1
                    ? t('toastNoteDeleted')
                    : t('toastNotesDeleted', { count: selectedIds.size }),
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

    const columns: ColumnDef<Note>[] = useMemo(
        () => [
            {
                key: 'content',
                label: t('columnContent'),
                getSortValue: (n) => n.content ?? null,
                render: (n) => (
                    <span className="block max-w-[28rem] truncate text-sm text-neutral-800">
                        {n.content}
                    </span>
                ),
            },
            {
                key: 'person',
                label: t('columnPerson'),
                getSortValue: (n) => (n.person ? personById.get(n.person)?.name ?? null : null),
                render: (n) =>
                    n.person ? personById.get(n.person)?.name ?? '—' : '—',
            },
            {
                key: 'deal',
                label: t('columnDeal'),
                getSortValue: (n) => (n.deal ? dealById.get(n.deal)?.name ?? null : null),
                render: (n) => (n.deal ? dealById.get(n.deal)?.name ?? '—' : '—'),
            },
            {
                key: 'author',
                label: t('columnAuthor'),
                getSortValue: (n) => userById.get(n.author)?.displayName ?? null,
                render: (n) => userById.get(n.author)?.displayName ?? '—',
            },
            {
                key: 'createdAt',
                label: t('columnCreated'),
                getSortValue: (n) => (n.createdAt ? Date.parse(n.createdAt) : null),
                render: (n) => formatDate(n.createdAt, locale),
            },
            {
                key: 'updatedAt',
                label: t('columnUpdated'),
                getSortValue: (n) => (n.updatedAt ? Date.parse(n.updatedAt) : null),
                render: (n) => formatDateTime(n.updatedAt, locale),
            },
        ],
        [t, locale, personById, dealById, userById],
    );

    const renderRowAvatar = (n: Note) => {
        const author = userById.get(n.author);
        return (
            <Avatar size="sm" className="ring-1 ring-black/5">
                {author?.profilePictureUrl ? (
                    <AvatarImage src={author.profilePictureUrl} alt={author.displayName} />
                ) : (
                    <AvatarFallback>
                        <UserIcon className="size-3 text-neutral-500" />
                    </AvatarFallback>
                )}
            </Avatar>
        );
    };

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <Button
                    className="bg-brand text-white"
                    aria-label={t('newAria')}
                    onClick={() => setCreating(true)}
                >
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
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

                {displayMode === 'grid' && (
                    <Select value={groupBy} onValueChange={(v) => setGroupBy(v as typeof groupBy)}>
                        <SelectTrigger
                            aria-label={t('groupByAria')}
                            className="flex h-auto items-center gap-2 rounded-full border-0 bg-neutral-100 px-4 py-2 text-sm text-neutral-700 ring-1 ring-black/5 shadow-none transition hover:bg-neutral-200 focus-visible:border-0 focus-visible:ring-1 focus-visible:ring-black/5"
                        >
                            <span className="text-neutral-500">{t('groupBy')}</span>
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="none">{t('groupNone')}</SelectItem>
                            <SelectItem value="person">{t('groupPerson')}</SelectItem>
                            <SelectItem value="company">{t('groupCompany')}</SelectItem>
                        </SelectContent>
                    </Select>
                )}

                {selectedIds.size > 0 && (
                    <div className="flex items-center gap-2">
                        <span className="text-sm text-neutral-500">
                            {t('selectedCount', { count: selectedIds.size })}
                        </span>
                        <ButtonGroup className="rounded-full bg-neutral-100">
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
                                    <DropdownMenuItem
                                        variant="destructive"
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            setDeleteDialogOpen(true);
                                        }}
                                    >
                                        <TrashIcon />
                                        {t('delete')}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </ButtonGroup>
                    </div>
                )}

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

            {filteredNotes.length === 0 ? (
                <div className="rounded-2xl bg-neutral-50 px-6 py-16 text-center ring-1 ring-black/5">
                    <p className="text-sm text-neutral-500">
                        {notes.length === 0 ? t('empty') : t('emptyFiltered')}
                    </p>
                </div>
            ) : displayMode === 'grid' && groupBy !== 'none' ? (
                <div className="space-y-8 pt-2">
                    {groups.map((g) => (
                        <section key={g.id}>
                            <div className="mb-3 flex items-center gap-2 px-2">
                                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                                    {g.label}
                                </h2>
                                {/* <span className="rounded-full bg-neutral-100 p-2 text-[10px] font-medium text-neutral-500">
                                    {g.notes.length}
                                </span> */}
                                <Badge variant="outline">{g.notes.length}</Badge>
                            </div>
                            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                                {g.notes.map((note) => (
                                    <NoteCard
                                        key={note.id}
                                        note={note}
                                        person={note.person ? personById.get(note.person) : undefined}
                                        deal={note.deal ? dealById.get(note.deal) : undefined}
                                        author={userById.get(note.author)}
                                        onEdit={() => openEdit(note)}
                                        onDelete={() => requestDelete(note)}
                                    />
                                ))}
                            </div>
                        </section>
                    ))}
                </div>
            ) : (
                <RecordsRenderView<Note>
                    data={filteredNotes}
                    columns={columns}
                    renderCard={(item, { onQuickEdit, onDelete }) => (
                        <NoteCard
                            note={item}
                            person={item.person ? personById.get(item.person) : undefined}
                            deal={item.deal ? dealById.get(item.deal) : undefined}
                            author={userById.get(item.author)}
                            onEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                            onDelete={onDelete ? () => onDelete(item) : undefined}
                        />
                    )}
                    renderAvatar={renderRowAvatar}
                    onRowClick={openEdit}
                    displayMode={displayMode}
                    selectedIds={selectedIds}
                    onSelectedIdsChange={setSelectedIds}
                    onQuickEdit={openEdit}
                    onDelete={requestDelete}
                    gridClassName="grid grid-cols-1 gap-4 pt-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
                    entityLabel={t('entityLabel')}
                />
            )}

            <NoteDialog
                open={creating || editingNote !== null}
                onOpenChange={closeNoteDialog}
                note={editingNote}
                persons={persons}
                deals={deals}
                currentUserId={currentUserId}
            />

            <QuickEditNoteSheet
                open={editSheetOpen}
                onOpenChange={setEditSheetOpen}
                selectedIds={selectedIds}
                selectedNotes={selectedNotes}
                drafts={drafts}
                updateDraft={updateDraft}
                persons={persons}
                deals={deals}
                isSaving={isSaving}
                saveEdits={saveEdits}
            />

            <DeleteRecordDialog
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedNotes}
                entityLabel={t('entityLabel')}
                getDisplayName={(n) => {
                    const snippet = n.content?.trim().slice(0, 40) ?? '';
                    return snippet.length === 40 ? `${snippet}…` : snippet;
                }}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}