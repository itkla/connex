'use client';

import { useEffect, useMemo, useState } from 'react';
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
import { deleteNote } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatDate } from '@/app/lib/utils';
import { deriveNoteTitle, noteSnippet } from '@/app/lib/noteText';
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

const STANDALONE = '__standalone';

export default function NotesBrowser({ notes, persons, deals, users }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityNotes');
    const tf = useTranslations('Filters');
    const locale = useLocale();

    const [query, setQuery] = useState('');
    const [groupBy, setGroupBy] = useState<GroupBy>('record');
    const [sortBy, setSortBy] = useState<SortBy>('updated');
    const [deleteTarget, setDeleteTarget] = useState<Note | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);

    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

    useEffect(() => {
        const noteParam = new URLSearchParams(window.location.search).get('note');
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
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise>
                    <div className="flex flex-wrap items-start justify-between gap-4">
                        <div>
                            <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                            <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                                {t('subtitle')}
                            </p>
                        </div>
                        <Button
                            className="bg-brand text-white hover:bg-brand-dark"
                            onClick={() => router.push('/activity/notes/new')}
                        >
                            <PlusIcon strokeWidth={2.5} />
                            {t('new')}
                        </Button>
                    </div>
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
                                    onClick={() => router.push('/activity/notes/new')}
                                    className="mt-6 bg-brand text-white hover:bg-brand-dark"
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
                                        <SectionHeader
                                            title={group.label}
                                            action={<Badge variant="outline">{group.notes.length}</Badge>}
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
                                                    delete: t('delete'),
                                                }}
                                                onOpen={() => router.push(`/activity/notes/${note.id}`)}
                                                onDelete={() => setDeleteTarget(note)}
                                            />
                                        ))}
                                    </ul>
                                </section>
                            ))}
                        </div>
                    )}
                </Rise>
            </div>

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
        </div>
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
    onDelete,
}: {
    note: Note;
    author: User | undefined;
    recordLabel: string | null;
    recordKind: 'person' | 'deal' | null;
    locale: string;
    labels: { untitled: string; actionsAria: string; open: string; delete: string };
    onOpen: () => void;
    onDelete: () => void;
}) {
    const title = deriveNoteTitle(note, labels.untitled);
    const snippet = noteSnippet(note.content);
    const authorName = author ? author.displayName || author.username : null;
    const RecordIcon = recordKind === 'deal' ? BriefcaseIcon : UserIcon;

    return (
        <li className="group relative flex items-center gap-3 px-6 py-3.5 transition-colors hover:bg-muted/40">
            <Link
                href={`/activity/notes/${note.id}`}
                className="flex min-w-0 flex-1 flex-col rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
            >
                <span className="truncate font-medium text-foreground">{title}</span>
                {snippet ? (
                    <span className="mt-0.5 truncate text-sm text-muted-foreground">{snippet}</span>
                ) : null}
            </Link>
            <div className="flex shrink-0 items-center gap-4">
                {recordLabel ? (
                    <span className="hidden items-center gap-1.5 text-xs text-muted-foreground sm:inline-flex">
                        <RecordIcon className="h-3.5 w-3.5" />
                        <span className="max-w-[10rem] truncate">{recordLabel}</span>
                    </span>
                ) : null}
                {author ? (
                    <Avatar size="sm" className="hidden ring-1 ring-border md:flex" title={authorName ?? undefined}>
                        <AvatarImage src={author.profilePictureUrl} alt={authorName ?? ''} />
                        <AvatarFallback>{(authorName ?? '?').slice(0, 1).toUpperCase()}</AvatarFallback>
                    </Avatar>
                ) : null}
                <span className="hidden w-20 shrink-0 text-right text-xs text-muted-foreground lg:block">
                    {formatDate(note.updatedAt ?? note.createdAt, locale)}
                </span>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={labels.actionsAria}
                            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground opacity-0 transition hover:bg-accent hover:text-foreground focus:opacity-100 group-hover:opacity-100"
                        >
                            <EllipsisHorizontalIcon className="h-5 w-5" />
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem onSelect={() => onOpen()}>
                            <ArrowUpRightIcon className="h-4 w-4" />
                            {labels.open}
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            className="text-destructive hover:bg-destructive/10"
                            onSelect={() => onDelete()}
                        >
                            <TrashIcon className="h-4 w-4" />
                            {labels.delete}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>
        </li>
    );
}
