'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { AnimatePresence, useReducedMotion } from 'motion/react';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { PlusIcon, EllipsisVerticalIcon, PencilIcon, TrashIcon } from '@heroicons/react/24/solid';
import {
    Squares2X2Icon,
    TableCellsIcon,
    UserIcon,
    PencilSquareIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

import { SearchField, FilterBar, RadioFilter, SegmentedToggle, type FilterChipData } from '@/app/components/filters';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import NoteContent from './NoteContent';
import { noteContentToPlainText } from '@/app/lib/references';
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
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;

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

    const groupByLabelOf = (g: 'none' | 'person' | 'company') =>
        g === 'person' ? t('groupPerson') : g === 'company' ? t('groupCompany') : t('groupNone');
    const groupActive = displayMode === 'grid' && groupBy !== 'none';
    const hasActiveFilters = query.trim() !== '' || groupActive;
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...(groupActive ? [{ id: 'group', label: groupByLabelOf(groupBy), onRemove: () => setGroupBy('none') }] : []),
    ];

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
                    <span className="block max-w-[28rem] truncate text-sm text-foreground">
                        <NoteContent content={n.content} references={n.references} />
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
            <Avatar size="sm" className="ring-1 ring-border">
                {author?.profilePictureUrl ? (
                    <AvatarImage src={author.profilePictureUrl} alt={author.displayName} />
                ) : (
                    <AvatarFallback>
                        <UserIcon className="size-3 text-muted-foreground" />
                    </AvatarFallback>
                )}
            </Avatar>
        );
    };

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
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
        <div className="page-grid gap-y-8">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t('subtitle')}</p>
                </div>
                <Button
                    className="bg-brand text-white hover:bg-brand-dark"
                    aria-label={t('newAria')}
                    onClick={() => setCreating(true)}
                >
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <FilterBar
                reduce={reduce}
                chips={chips}
                hasActiveFilters={hasActiveFilters}
                onClearAll={() => { setQuery(''); setGroupBy('none'); }}
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
                    <SegmentedToggle
                        ariaLabel={t('displayModeAria')}
                        value={displayMode}
                        onChange={setDisplayMode}
                        options={[
                            { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('gridViewAria') },
                            { value: 'table', icon: <TableCellsIcon className="size-4" />, ariaLabel: t('tableViewAria') },
                        ]}
                    />
                }
            >
                {displayMode === 'grid' && (
                    <RadioFilter
                        label={t('groupBy')}
                        ariaLabel={t('groupByAria')}
                        value={groupBy}
                        onValueChange={(v) => setGroupBy(v as typeof groupBy)}
                        options={[
                            { value: 'none', label: t('groupNone') },
                            { value: 'person', label: t('groupPerson') },
                            { value: 'company', label: t('groupCompany') },
                        ]}
                    />
                )}
            </FilterBar>

            {filteredNotes.length === 0 ? (
                <div className="rounded-2xl bg-card px-6 py-20 text-center ring-1 ring-border">
                    <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                        <PencilSquareIcon className="size-7" />
                    </div>
                    <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">
                        {notes.length === 0 ? t('empty') : t('emptyFiltered')}
                    </p>
                    {notes.length === 0 && (
                        <Button
                            onClick={() => setCreating(true)}
                            className="mt-6 bg-brand text-white hover:bg-brand-dark"
                        >
                            <PlusIcon strokeWidth={2.5} />
                            {t('new')}
                        </Button>
                    )}
                </div>
            ) : displayMode === 'grid' ? (
                <div className="space-y-8">
                    {groups.map((g) => (
                        <section key={g.id}>
                            {g.label && (
                                <div className="mb-3 flex items-center gap-2 px-1">
                                    <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                        {g.label}
                                    </h2>
                                    <Badge variant="outline">{g.notes.length}</Badge>
                                </div>
                            )}
                            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                                <AnimatePresence mode="popLayout" initial={false}>
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
                                </AnimatePresence>
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
                    entityLabel={t('entityLabel')}
                    selectionActions={selectionActions}
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
                    const snippet = noteContentToPlainText(n.content ?? '').trim().slice(0, 40);
                    return snippet.length === 40 ? `${snippet}…` : snippet;
                }}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}