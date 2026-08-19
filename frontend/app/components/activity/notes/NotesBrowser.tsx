'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { PlusIcon } from '@heroicons/react/24/solid';
import {
    ArrowUpRightIcon,
    BriefcaseIcon,
    EllipsisHorizontalIcon,
    PencilSquareIcon,
    TrashIcon,
    UserIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { SearchField, FilterBar, RadioFilter, type FilterChipData } from '@/app/components/filters';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { deleteNote } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatDate } from '@/app/lib/utils';
import { deriveNoteTitle, noteSnippet } from '@/app/lib/noteText';
import { recordDetailNavigationPath } from '@/app/lib/recordReturnPath';
import { NOTE_URL_KEY } from '@/app/hooks/listStateUrl';
import { useRecordReturnScroll } from '@/app/hooks/useRecordReturnSelection';
import type { Contact, Deal, Note, User } from '@/app/lib/types';

type Props = {
    notes: Note[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
};

type GroupBy = 'record' | 'none';
type SortBy = 'updated' | 'created' | 'title';
type NoteGroup = { id: string; label: string | null; notes: Note[] };
type VisibleNoteGroup = NoteGroup & { total: number };

const STANDALONE = '__standalone';
const INITIAL_VISIBLE_NOTES = 40;
const NOTES_PAGE_SIZE = 40;

export default function NotesBrowser({ notes, persons, deals, users, currentUserId }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityNotes');
    const tf = useTranslations('Filters');
    const locale = useLocale();

    const [query, setQuery] = useState('');
    const [groupBy, setGroupBy] = useState<GroupBy>('record');
    const [sortBy, setSortBy] = useState<SortBy>('updated');
    const [deleteTarget, setDeleteTarget] = useState<Note | null>(null);
    const [dialogNote, setDialogNote] = useState<Note | null | undefined>(undefined);
    const [isDeleting, setIsDeleting] = useState(false);
    const visibleKey = `${query.trim()}|${groupBy}|${sortBy}`;
    const [visibleState, setVisibleState] = useState({ key: visibleKey, count: INITIAL_VISIBLE_NOTES });
    const visibleCount = visibleState.key === visibleKey ? visibleState.count : INITIAL_VISIBLE_NOTES;

    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

    useEffect(() => {
        const noteParam = new URLSearchParams(window.location.search).get(NOTE_URL_KEY);
        if (noteParam && /^\d+$/.test(noteParam)) {
            router.replace(`/activity/notes/${noteParam}`);
        }
    }, [router]);

    const filtered = useMemo(() => {
        const needle = query.trim().toLowerCase();
        if (!needle) return notes;
        return notes.filter((note) => {
            const author = userById.get(note.author);
            const haystack = [
                note.title,
                noteSnippet(note.content, 4000),
                note.person ? personById.get(note.person)?.name : null,
                note.deal ? dealById.get(note.deal)?.name : null,
                author?.displayName,
                author?.username,
            ]
                .filter(Boolean)
                .join(' ')
                .toLowerCase();
            return haystack.includes(needle);
        });
    }, [notes, query, personById, dealById, userById]);
    const restoreVisibleCount = useCallback((count: number) => {
        setVisibleState({
            key: visibleKey,
            count: Math.min(Math.max(count, INITIAL_VISIBLE_NOTES), filtered.length),
        });
    }, [filtered.length, visibleKey]);
    const returnSnapshot = useRecordReturnScroll('notes', true, visibleCount, restoreVisibleCount);

    const groups = useMemo<NoteGroup[]>(() => {
        const sorted = [...filtered].sort((a, b) => {
            if (sortBy === 'title') {
                return deriveNoteTitle(a).localeCompare(deriveNoteTitle(b), locale);
            }
            const key = sortBy === 'created' ? 'createdAt' : 'updatedAt';
            return (b[key] ?? '').localeCompare(a[key] ?? '');
        });

        if (groupBy === 'none') {
            return [{ id: '__all', label: null, notes: sorted }];
        }

        const map = new Map<string, NoteGroup>();
        for (const note of sorted) {
            let id = STANDALONE;
            let label = t('standalone');
            if (note.person) {
                id = `person:${note.person}`;
                label = personById.get(note.person)?.name ?? t('standalone');
            } else if (note.deal) {
                id = `deal:${note.deal}`;
                label = dealById.get(note.deal)?.name ?? t('standalone');
            }
            let group = map.get(id);
            if (!group) {
                group = { id, label, notes: [] };
                map.set(id, group);
            }
            group.notes.push(note);
        }
        return Array.from(map.values()).sort((a, b) => {
            if (a.id === STANDALONE) return 1;
            if (b.id === STANDALONE) return -1;
            if (sortBy === 'title') {
                return (a.label ?? '').localeCompare(b.label ?? '', locale);
            }
            const key = sortBy === 'created' ? 'createdAt' : 'updatedAt';
            return (b.notes[0]?.[key] ?? '').localeCompare(a.notes[0]?.[key] ?? '');
        });
    }, [filtered, groupBy, sortBy, locale, personById, dealById, t]);

    const visibleGroups = useMemo<VisibleNoteGroup[]>(() => {
        let remaining = visibleCount;
        const visible: VisibleNoteGroup[] = [];
        for (const group of groups) {
            if (remaining === 0) break;
            const groupNotes = group.notes.slice(0, remaining);
            if (groupNotes.length > 0) {
                visible.push({ ...group, notes: groupNotes, total: group.notes.length });
                remaining -= groupNotes.length;
            }
        }
        return visible;
    }, [groups, visibleCount]);
    const shownCount = Math.min(visibleCount, filtered.length);
    const hasMore = shownCount < filtered.length;

    const hasActiveFilters = query.trim() !== '' || groupBy !== 'record' || sortBy !== 'updated';
    const chips: FilterChipData[] = query.trim()
        ? [{ id: 'query', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }]
        : [];

    const confirmDelete = async () => {
        if (!deleteTarget) return;
        setIsDeleting(true);
        try {
            await deleteNote(deleteTarget.id);
            toastSuccess(t('toastNoteDeleted'));
            setDeleteTarget(null);
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('toastFailedDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const deleteIds = useMemo(
        () => new Set<number>(deleteTarget ? [deleteTarget.id] : []),
        [deleteTarget],
    );

    return (
        <>
            <PageShell>
                <Rise>
                    <PageHeader
                        title={t('title')}
                        description={t('subtitle')}
                        actions={
                            <Button
                                variant="brand"
                                onClick={() => setDialogNote(null)}
                            >
                                <PlusIcon strokeWidth={2.5} />
                                {t('new')}
                            </Button>
                        }
                    />
                </Rise>

                <Rise delay={0.06}>
                    <FilterBar
                        reduce={false}
                        chips={chips}
                        hasActiveFilters={hasActiveFilters}
                        onClearAll={() => {
                            setQuery('');
                            setGroupBy('record');
                            setSortBy('updated');
                        }}
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
                    >
                        <RadioFilter
                            label={t('groupBy')}
                            ariaLabel={t('groupByAria')}
                            value={groupBy}
                            onValueChange={(value) => setGroupBy(value as GroupBy)}
                            options={[
                                { value: 'record', label: t('groupRecord') },
                                { value: 'none', label: t('groupNone') },
                            ]}
                        />
                        <RadioFilter
                            label={t('sortBy')}
                            ariaLabel={t('sortByAria')}
                            value={sortBy}
                            onValueChange={(value) => setSortBy(value as SortBy)}
                            options={[
                                { value: 'updated', label: t('sortUpdated') },
                                { value: 'created', label: t('sortCreated') },
                                { value: 'title', label: t('sortTitle') },
                            ]}
                        />
                    </FilterBar>
                </Rise>

                <Rise delay={0.12}>
                    {filtered.length === 0 ? (
                        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
                            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                                <PencilSquareIcon className="size-7" />
                            </div>
                            <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">
                                {notes.length === 0 ? t('empty') : t('emptyFiltered')}
                            </p>
                            {notes.length === 0 && (
                                <Button
                                    onClick={() => setDialogNote(null)}
                                    variant="brand"
                                    className="mt-6"
                                >
                                    <PlusIcon strokeWidth={2.5} />
                                    {t('new')}
                                </Button>
                            )}
                        </div>
                    ) : (
                        <div className="space-y-8">
                            {visibleGroups.map((group) => (
                                <section key={group.id}>
                                    {group.label && (
                                        <SectionHeader
                                            title={group.label}
                                            action={<Badge variant="outline">{group.total}</Badge>}
                                        />
                                    )}
                                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                        {group.notes.map((note) => (
                                            <NoteRow
                                                key={note.id}
                                                note={note}
                                                author={userById.get(note.author)}
                                                recordLabel={
                                                    note.person
                                                        ? personById.get(note.person)?.name ?? null
                                                        : note.deal
                                                          ? dealById.get(note.deal)?.name ?? null
                                                          : null
                                                }
                                                recordKind={note.person ? 'person' : note.deal ? 'deal' : null}
                                                locale={locale}
                                                labels={{
                                                    untitled: t('untitled'),
                                                    actionsAria: t('actionsAria'),
                                                    open: t('open'),
                                                    edit: t('edit'),
                                                    delete: t('delete'),
                                                }}
                                                onOpen={() => router.push(
                                                    recordDetailNavigationPath('notes', note.id, returnSnapshot),
                                                )}
                                                onEdit={() => setDialogNote(note)}
                                                onDelete={() => setDeleteTarget(note)}
                                            />
                                        ))}
                                    </ul>
                                </section>
                            ))}
                            <div className="flex flex-col items-center gap-3">
                                <p className="text-xs text-muted-foreground">
                                    {t('showingCount', { shown: shownCount, total: filtered.length })}
                                </p>
                                {hasMore ? (
                                    <Button
                                        variant="outline"
                                        onClick={() => setVisibleState({
                                            key: visibleKey,
                                            count: Math.min(visibleCount + NOTES_PAGE_SIZE, filtered.length),
                                        })}
                                    >
                                        {t('showMore')}
                                    </Button>
                                ) : null}
                            </div>
                        </div>
                    )}
                </Rise>
            </PageShell>

            {dialogNote !== undefined ? (
                <NoteDialog
                    key={dialogNote ? `edit-${dialogNote.id}` : 'create'}
                    open
                    onOpenChange={(open) => {
                        if (!open) setDialogNote(undefined);
                    }}
                    note={dialogNote}
                    persons={persons}
                    deals={deals}
                    currentUserId={currentUserId}
                />
            ) : null}

            <DeleteRecordDialog
                open={deleteTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setDeleteTarget(null);
                }}
                selectedIds={deleteIds}
                selectedItems={deleteTarget ? [deleteTarget] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(note) => deriveNoteTitle(note, t('untitled'))}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </>
    );
}

function NoteRow({
    note,
    author,
    recordLabel,
    recordKind,
    locale,
    labels,
    onOpen,
    onEdit,
    onDelete,
}: {
    note: Note;
    author: User | undefined;
    recordLabel: string | null;
    recordKind: 'person' | 'deal' | null;
    locale: string;
    labels: { untitled: string; actionsAria: string; open: string; edit: string; delete: string };
    onOpen: () => void;
    onEdit: () => void;
    onDelete: () => void;
}) {
    const title = deriveNoteTitle(note, labels.untitled);
    const snippet = noteSnippet(note.content);
    const authorName = author ? author.displayName || author.username : null;
    const RecordIcon = recordKind === 'deal' ? BriefcaseIcon : UserIcon;

    return (
        <li className="group relative flex items-start gap-3 px-5 py-4 transition-colors hover:bg-muted/40">
            {author ? (
                <Avatar size="sm" className="mt-0.5 hidden ring-1 ring-border sm:flex" title={authorName ?? undefined}>
                    <AvatarImage src={author.profilePictureUrl} alt="" />
                    <AvatarFallback>{(authorName ?? '?').slice(0, 1).toUpperCase()}</AvatarFallback>
                </Avatar>
            ) : null}
            <Link
                href={`/activity/notes/${note.id}`}
                onClick={(event) => {
                    if (
                        event.defaultPrevented
                        || event.button !== 0
                        || event.metaKey
                        || event.ctrlKey
                        || event.shiftKey
                        || event.altKey
                    ) {
                        return;
                    }
                    event.preventDefault();
                    onOpen();
                }}
                className="flex min-w-0 flex-1 flex-col rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
            >
                <span className="truncate font-medium text-foreground">{title}</span>
                {snippet ? (
                    <span className="mt-0.5 line-clamp-2 text-sm text-muted-foreground">{snippet}</span>
                ) : null}
                <span className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    {authorName ? <span>{authorName}</span> : null}
                    {recordLabel ? (
                        <span className="inline-flex min-w-0 items-center gap-1.5">
                            <RecordIcon className="size-3.5 shrink-0" />
                            <span className="max-w-[12rem] truncate">{recordLabel}</span>
                        </span>
                    ) : null}
                    <span>{formatDate(note.updatedAt ?? note.createdAt, locale)}</span>
                </span>
            </Link>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={labels.actionsAria}
                        className="flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground opacity-100 transition hover:bg-accent hover:text-foreground focus:opacity-100 sm:opacity-0 sm:group-hover:opacity-100"
                    >
                        <EllipsisHorizontalIcon className="size-5" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    <DropdownMenuItem onSelect={() => onOpen()}>
                        <ArrowUpRightIcon className="size-4" />
                        {labels.open}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => onEdit()}>
                        <PencilSquareIcon className="size-4" />
                        {labels.edit}
                    </DropdownMenuItem>
                    <DropdownMenuItem
                        className="text-destructive hover:bg-destructive/10"
                        onSelect={() => onDelete()}
                    >
                        <TrashIcon className="size-4" />
                        {labels.delete}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </li>
    );
}
