'use client';

import { useMemo, useState } from 'react';
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
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
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
import { deleteNote, getNotesPage } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatDate } from '@/app/lib/utils';
import { deriveNoteTitle, noteSnippet } from '@/app/lib/noteText';
import { recordDetailNavigationPath } from '@/app/lib/recordReturnPath';
import { useRecordReturnScroll } from '@/app/hooks/useRecordReturnSelection';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import type { Contact, Deal, Note, NotesPageParams, User } from '@/app/lib/types';

type Props = {
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
};

type GroupBy = 'record' | 'none';
type SortBy = 'updated' | 'created' | 'title';
type NoteGroup = { id: string; label: string | null; notes: Note[] };

const STANDALONE = '__standalone';
const NOTES_PAGE_SIZE = 40;

function isSortBy(value: string | null | undefined): value is SortBy {
    return value === 'updated' || value === 'created' || value === 'title';
}

function loadNotesPage(params: NotesPageParams) {
    const recognizedSort = isSortBy(params.sort) ? params.sort : undefined;
    const sort = recognizedSort ?? 'updated';
    const dir = recognizedSort ? params.dir : 'desc';
    return getNotesPage({ ...params, sort, dir });
}

export default function NotesBrowser({ persons, deals, users, currentUserId }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityNotes');
    const tf = useTranslations('Filters');
    const locale = useLocale();

    const [groupBy, setGroupBy] = useState<GroupBy>('record');
    const [deleteTarget, setDeleteTarget] = useState<Note | null>(null);
    const [dialogNote, setDialogNote] = useState<Note | null | undefined>(undefined);
    const [isDeleting, setIsDeleting] = useState(false);
    const {
        items: notes,
        total,
        loading,
        page,
        setPage,
        size,
        query,
        setQuery,
        sortKey,
        applySort,
        reload,
    } = useServerRecords<Note, NotesPageParams>(loadNotesPage, {}, {
        defaultSize: NOTES_PAGE_SIZE,
        urlSync: true,
    });
    const sortBy: SortBy = isSortBy(sortKey) ? sortKey : 'updated';

    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

    const returnSnapshot = useRecordReturnScroll('notes', !loading);

    const groups = useMemo<NoteGroup[]>(() => {
        if (groupBy === 'none') {
            return [{ id: '__all', label: null, notes }];
        }

        const map = new Map<string, NoteGroup>();
        for (const note of notes) {
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
        const grouped = Array.from(map.values());
        return [
            ...grouped.filter((group) => group.id !== STANDALONE),
            ...grouped.filter((group) => group.id === STANDALONE),
        ];
    }, [groupBy, notes, personById, dealById, t]);
    const pageCount = Math.max(1, Math.ceil(total / size));

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
            reload();
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
                            applySort(null, 'asc');
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
                            onValueChange={(value) => {
                                if (!isSortBy(value)) return;
                                applySort(value, value === 'title' ? 'asc' : 'desc');
                            }}
                            options={[
                                { value: 'updated', label: t('sortUpdated') },
                                { value: 'created', label: t('sortCreated') },
                                { value: 'title', label: t('sortTitle') },
                            ]}
                        />
                    </FilterBar>
                </Rise>

                <Rise delay={0.12}>
                    {loading && notes.length === 0 ? (
                        <div className="space-y-3" role="status" aria-label={t('loading')}>
                            {Array.from({ length: 5 }, (_, index) => (
                                <Skeleton key={index} className="h-24 rounded-2xl" />
                            ))}
                        </div>
                    ) : notes.length === 0 ? (
                        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
                            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                                <PencilSquareIcon className="size-7" />
                            </div>
                            <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">
                                {query.trim() ? t('emptyFiltered') : t('empty')}
                            </p>
                            {!query.trim() && (
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
                            {groups.map((group) => (
                                <section key={group.id}>
                                    {group.label && (
                                        <SectionHeader title={group.label} />
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
                                    {t('showingCount', { shown: notes.length, total })}
                                </p>
                                {pageCount > 1 ? (
                                    <Pagination aria-label={t('paginationLabel')}>
                                        <PaginationContent>
                                            <PaginationItem>
                                                <PaginationPrevious
                                                    aria-label={t('previousPage')}
                                                    disabled={page <= 1 || loading}
                                                    onClick={() => setPage(page - 1)}
                                                />
                                            </PaginationItem>
                                            <PaginationItem>
                                                <span className="px-3 text-xs tabular-nums text-muted-foreground">
                                                    {t('pageStatus', { page, total: pageCount })}
                                                </span>
                                            </PaginationItem>
                                            <PaginationItem>
                                                <PaginationNext
                                                    aria-label={t('nextPage')}
                                                    disabled={page >= pageCount || loading}
                                                    onClick={() => setPage(page + 1)}
                                                />
                                            </PaginationItem>
                                        </PaginationContent>
                                    </Pagination>
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
                    onSaved={reload}
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
