'use client';

/* eslint-disable @next/next/no-img-element */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import {
    ArrowDownTrayIcon,
    ArrowTopRightOnSquareIcon,
    Bars3Icon,
    CheckIcon,
    ChevronUpDownIcon,
    EllipsisVerticalIcon,
    LinkSlashIcon,
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TagIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { Checkbox } from '@/components/ui/checkbox';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

import {
    addAttachmentTag,
    deleteAttachment,
    getAttachment,
    getAttachmentFacets,
    getAttachmentsPage,
    getTags,
} from '@/app/lib/api';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { deleteUploadedFile, formatDate, formatFileSize } from '@/app/lib/utils';
import type { Attachment, AttachmentFacets, AttachmentsPageParams, PageParams, Tag } from '@/app/lib/types';
import {
    classifyKind,
    FILE_KINDS,
    KIND_ICON,
    KIND_LABEL_KEY,
    SOURCE_TYPES,
    sourceMetaFor,
    type FileKind,
    type SourceType,
} from '@/app/components/library/files/fileMeta';

import { useUrlSync } from '@/app/hooks/useUrlSync';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { type ColumnDef } from '@/app/components/records/types';
import FileActionsMenu from '@/app/components/library/files/FileActionsMenu';
import FileGlyph from '@/app/components/library/files/FileGlyph';
import OwnerChip from '@/app/components/library/files/OwnerChip';
import IconLink from '@/app/components/library/files/IconLink';
import ViewButton from '@/app/components/library/files/ViewButton';
import FilterMenu from '@/app/components/library/files/FilterMenu';
import MenuChoice from '@/app/components/library/files/MenuChoice';
import EmptyState from '@/app/components/library/files/EmptyState';
import FileDetailSheet from '@/app/components/library/files/FileDetailSheet';
import FileTagChips from '@/app/components/library/files/FileTagChips';

type SortKey = 'newest' | 'oldest' | 'name' | 'largest';
type ViewMode = 'grid' | 'list';
type T = ReturnType<typeof useTranslations>;

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const PAGE_SIZE = 25;

const SORT_KEYS: SortKey[] = ['newest', 'oldest', 'name', 'largest'];
const SORT_LABEL_KEY: Record<SortKey, string> = {
    newest: 'sortNewest',
    oldest: 'sortOldest',
    name: 'sortName',
    largest: 'sortLargest',
};
const SOURCE_LABEL_KEY: Record<SourceType, string> = {
    company: 'sourceCompany',
    person: 'sourcePerson',
    deal: 'sourceDeal',
    user: 'sourceUser',
};

function normalizeKind(v: string | null): FileKind | 'all' {
    return v && (FILE_KINDS as readonly string[]).includes(v) ? (v as FileKind) : 'all';
}
function normalizeSource(v: string | null): SourceType | 'all' {
    return v && (SOURCE_TYPES as readonly string[]).includes(v) ? (v as SourceType) : 'all';
}
function normalizeSort(v: string | null): SortKey {
    return v && (SORT_KEYS as readonly string[]).includes(v) ? (v as SortKey) : 'newest';
}

export default function FilesBrowser() {
    const t = useTranslations('LibraryFiles');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const searchParams = useSearchParams();

    const [kind, setKind] = useState<FileKind | 'all'>(() => normalizeKind(searchParams.get('kind')));
    const [source, setSource] = useState<SourceType | 'all'>(() => normalizeSource(searchParams.get('source')));
    const [sort, setSort] = useState<SortKey>(() => normalizeSort(searchParams.get('sort')));
    const [tagIds, setTagIds] = useState<number[]>(() => {
        const raw = searchParams.get('tags');
        return raw ? raw.split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0) : [];
    });
    const [orphaned, setOrphaned] = useState<boolean>(() => searchParams.get('orphaned') === '1');
    const [view, setView] = useState<ViewMode>('grid');

    const [facets, setFacets] = useState<AttachmentFacets | null>(null);
    const [allTags, setAllTags] = useState<Tag[]>([]);
    const [deleting, setDeleting] = useState<Attachment | null>(null);
    const [busy, setBusy] = useState(false);
    const [detailFile, setDetailFile] = useState<Attachment | null>(null);
    const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
    const [bulkDeleting, setBulkDeleting] = useState(false);
    const [bulkBusy, setBulkBusy] = useState(false);

    const extraParams = useMemo(() => {
        const p: { sort: SortKey; types?: string[]; kinds?: string[]; tagIds?: number[]; orphaned?: boolean } = { sort };
        if (source !== 'all') p.types = [source];
        if (kind !== 'all') p.kinds = [kind];
        if (tagIds.length) p.tagIds = tagIds;
        if (orphaned) p.orphaned = true;
        return p as Omit<AttachmentsPageParams, keyof PageParams>;
    }, [sort, source, kind, tagIds, orphaned]);

    const { items, total, loading, page, setPage, size, setSize, query, setQuery, reload } = useServerRecords<
        Attachment,
        AttachmentsPageParams
    >(getAttachmentsPage, extraParams, PAGE_SIZE);

    // seed the search box + deep-linked file from the URL once on mount
    useEffect(() => {
        const q = searchParams.get('q');
        if (q) setQuery(q);
        const fileId = searchParams.get('file');
        if (fileId && /^\d+$/.test(fileId)) {
            getAttachment(Number(fileId))
                .then(setDetailFile)
                .catch(() => {});
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const loadFacets = useCallback(() => {
        getAttachmentFacets()
            .then(setFacets)
            .catch(() => setFacets(null));
    }, []);
    useEffect(() => {
        loadFacets();
        getTags()
            .then(setAllTags)
            .catch(() => setAllTags([]));
    }, [loadFacets]);

    // keep the URL in sync so a filtered view is shareable / deep-linkable
    useUrlSync({
        q: query || undefined,
        kind: kind !== 'all' ? kind : undefined,
        source: source !== 'all' ? source : undefined,
        sort: sort !== 'newest' ? sort : undefined,
        tags: tagIds.length ? tagIds.join(',') : undefined,
        orphaned: orphaned ? '1' : undefined,
        file: detailFile ? String(detailFile.id) : undefined,
    });

    // selection is scoped to the loaded page; drop it whenever the result set changes
    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => setSelectedIds(new Set()), [items]);

    const kindOptions = useMemo(() => {
        const counts = new Map((facets?.kinds ?? []).map((f) => [f.key, f.count]));
        return FILE_KINDS.filter((k) => counts.has(k)).map((k) => ({ kind: k, count: counts.get(k)! }));
    }, [facets]);

    const sourceOptions = useMemo(() => {
        const counts = new Map((facets?.sources ?? []).map((f) => [f.key, f.count]));
        return SOURCE_TYPES.filter((s) => counts.has(s)).map((s) => ({ source: s, count: counts.get(s)! }));
    }, [facets]);

    const tagById = useMemo(() => new Map(allTags.map((tg) => [tg.id, tg])), [allTags]);
    const tagOptions = useMemo(() => {
        return (facets?.tags ?? [])
            .map((f) => ({ tag: tagById.get(Number(f.key)), count: f.count }))
            .filter((o): o is { tag: Tag; count: number } => Boolean(o.tag))
            .sort((a, b) => b.count - a.count || a.tag.name.localeCompare(b.tag.name));
    }, [facets, tagById]);
    const toggleTag = (id: number) =>
        setTagIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

    const isEmptyLibrary = facets !== null && facets.total === 0;
    const filtersActive =
        query.trim() !== '' || kind !== 'all' || source !== 'all' || tagIds.length > 0 || orphaned;

    const clearFilters = () => {
        setQuery('');
        setKind('all');
        setSource('all');
        setTagIds([]);
        setOrphaned(false);
    };

    const selectedAttachments = useMemo(
        () => items.filter((a) => selectedIds.has(a.id)),
        [items, selectedIds],
    );
    const allOnPageSelected = items.length > 0 && items.every((a) => selectedIds.has(a.id));

    const toggleSelect = (id: number) =>
        setSelectedIds((prev) => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    const toggleSelectAll = () =>
        setSelectedIds((prev) => (items.every((a) => prev.has(a.id)) ? new Set() : new Set(items.map((a) => a.id))));

    const handleDelete = async () => {
        if (!deleting) return;
        setBusy(true);
        try {
            await deleteAttachment(deleting.id);
            await deleteUploadedFile(deleting.url);
            toastSuccess(t('toastDeleted'));
            setDeleting(null);
            reload();
            loadFacets();
        } catch {
            toastError(t('toastDeleteFailed'));
        } finally {
            setBusy(false);
        }
    };

    const bulkAddTag = async (tagId: number) => {
        if (selectedAttachments.length === 0) return;
        setBulkBusy(true);
        try {
            await Promise.all(selectedAttachments.map((a) => addAttachmentTag(a.id, tagId)));
            toastSuccess(t('toastTagged', { count: selectedAttachments.length }));
            reload();
            loadFacets();
        } catch {
            toastError(t('toastTagFailed'));
        } finally {
            setBulkBusy(false);
        }
    };

    const bulkDelete = async () => {
        if (selectedAttachments.length === 0) return;
        setBulkBusy(true);
        try {
            await Promise.all(
                selectedAttachments.map((a) => deleteAttachment(a.id).then(() => deleteUploadedFile(a.url))),
            );
            toastSuccess(t('toastDeletedCount', { count: selectedAttachments.length }));
            setBulkDeleting(false);
            reload();
            loadFacets();
        } catch {
            toastError(t('toastDeleteFailed'));
        } finally {
            setBulkBusy(false);
        }
    };

    const typeLabel = kind === 'all' ? t('typeAll') : t(KIND_LABEL_KEY[kind]);
    const sourceLabel = source === 'all' ? t('sourceAll') : t(SOURCE_LABEL_KEY[source]);

    // list-mode columns for RecordsRenderView (sorting stays on the toolbar dropdown)
    const columns: ColumnDef<Attachment>[] = useMemo(
        () => [
            {
                key: 'name',
                label: t('columnName'),
                render: (a) => (
                    <div className="min-w-0">
                        <div className="truncate font-medium text-foreground">{a.fileName}</div>
                        <div className="truncate text-xs text-muted-foreground">
                            {t('by', { name: a.uploadedByName || t('unknownUser') })}
                        </div>
                    </div>
                ),
            },
            {
                key: 'tags',
                label: t('tagsTitle'),
                render: (a) => <FileTagChips tags={a.tags} />,
                widthClass: 'w-44',
            },
            {
                key: 'source',
                label: t('detailRecord'),
                render: (a) => (
                    <span className="inline-flex max-w-full" onClick={(e) => e.stopPropagation()}>
                        <OwnerChip attachment={a} t={t} className="max-w-[12rem]" />
                    </span>
                ),
                widthClass: 'w-48',
            },
            {
                key: 'modified',
                label: t('detailAdded'),
                render: (a) => <span className="text-muted-foreground">{formatDate(a.createdAt, locale)}</span>,
                widthClass: 'w-32',
            },
            {
                key: 'size',
                label: t('detailSize'),
                render: (a) => (
                    <span className="tabular-nums text-muted-foreground">{formatFileSize(a.size)}</span>
                ),
                widthClass: 'w-24',
            },
            {
                key: 'actions',
                label: '',
                render: (a) => (
                    <span
                        className="flex items-center justify-end gap-0.5"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <IconLink href={a.url} label={t('open')} Icon={ArrowTopRightOnSquareIcon} openInNewTab />
                        <IconLink href={a.url} label={t('download')} Icon={ArrowDownTrayIcon} download={a.fileName} />
                        <button
                            type="button"
                            onClick={() => setDeleting(a)}
                            title={t('delete')}
                            aria-label={t('delete')}
                            className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                        >
                            <TrashIcon className="size-4" />
                        </button>
                    </span>
                ),
                widthClass: 'w-28',
            },
        ],
        [t, locale],
    );

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
            {allTags.length > 0 && (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="outline" size="sm" disabled={bulkBusy}>
                            <TagIcon className="size-4" />
                            {t('tagAction')}
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent side="top" align="center" className="max-h-64 overflow-y-auto">
                        <DropdownMenuLabel>{t('tagAction')}</DropdownMenuLabel>
                        <DropdownMenuSeparator />
                        {allTags.map((tag) => (
                            <DropdownMenuItem key={tag.id} onSelect={() => bulkAddTag(tag.id)}>
                                <span
                                    className="size-2.5 shrink-0 rounded-full"
                                    style={{ backgroundColor: tag.color }}
                                />
                                <span className="flex-1 truncate">{tag.name}</span>
                            </DropdownMenuItem>
                        ))}
                    </DropdownMenuContent>
                </DropdownMenu>
            )}
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button
                        variant="outline"
                        size="sm"
                        disabled={bulkBusy}
                        aria-label={t('actionsAria', { name: t('selectedCount', { count: selectedIds.size }) })}
                    >
                        {bulkBusy ? (
                            <Loader2Icon className="size-4 animate-spin" />
                        ) : (
                            <EllipsisVerticalIcon className="size-4" />
                        )}
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent side="top" align="end">
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            toggleSelectAll();
                        }}
                    >
                        <CheckIcon className="size-4" />
                        {allOnPageSelected ? t('selectNone') : t('selectAll')}
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                        variant="destructive"
                        onSelect={(e) => {
                            e.preventDefault();
                            setBulkDeleting(true);
                        }}
                    >
                        <TrashIcon className="size-4" />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </ButtonGroup>
    );

    return (
        <div className="space-y-8">
            <header className="flex flex-wrap items-end justify-between gap-4">
                <div>
                    <h1 className="text-4xl font-extrabold tracking-tight">{t('title')}</h1>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t('subtitle')}</p>
                </div>
                {facets && facets.total > 0 && (
                    <div className="text-right tabular-nums">
                        <div className="text-sm font-medium text-foreground">{t('count', { count: facets.total })}</div>
                        <div className="text-xs text-muted-foreground">
                            {t('totalSize', { size: formatFileSize(facets.totalSize) })}
                        </div>
                    </div>
                )}
            </header>

            {isEmptyLibrary ? (
                <EmptyState t={t} />
            ) : (
                <>
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <div className="flex flex-wrap items-center gap-2">
                            {kindOptions.length > 1 && (
                                <FilterMenu current={typeLabel} active={kind !== 'all'} srLabel={t('typeLabel')}>
                                    <DropdownMenuLabel>{t('typeLabel')}</DropdownMenuLabel>
                                    <DropdownMenuSeparator />
                                    <MenuChoice label={t('typeAll')} active={kind === 'all'} onSelect={() => setKind('all')} />
                                    {kindOptions.map(({ kind: k, count }) => (
                                        <MenuChoice
                                            key={k}
                                            Icon={KIND_ICON[k]}
                                            label={t(KIND_LABEL_KEY[k])}
                                            count={count}
                                            active={kind === k}
                                            onSelect={() => setKind(k)}
                                        />
                                    ))}
                                </FilterMenu>
                            )}
                            {sourceOptions.length > 1 && (
                                <FilterMenu current={sourceLabel} active={source !== 'all'} srLabel={t('sourceLabel')}>
                                    <DropdownMenuLabel>{t('sourceLabel')}</DropdownMenuLabel>
                                    <DropdownMenuSeparator />
                                    <MenuChoice
                                        label={t('sourceAll')}
                                        active={source === 'all'}
                                        onSelect={() => setSource('all')}
                                    />
                                    {sourceOptions.map(({ source: s, count }) => {
                                        const meta = sourceMetaFor(s);
                                        return (
                                            <MenuChoice
                                                key={s}
                                                Icon={meta?.Icon}
                                                label={t(SOURCE_LABEL_KEY[s])}
                                                count={count}
                                                active={source === s}
                                                onSelect={() => setSource(s)}
                                            />
                                        );
                                    })}
                                </FilterMenu>
                            )}
                            {tagOptions.length > 0 && (
                                <FilterMenu
                                    current={tagIds.length === 0 ? t('tagAll') : t('tagCount', { count: tagIds.length })}
                                    active={tagIds.length > 0}
                                    srLabel={t('tagLabel')}
                                >
                                    <DropdownMenuLabel>{t('tagLabel')}</DropdownMenuLabel>
                                    <DropdownMenuSeparator />
                                    {tagIds.length > 0 && (
                                        <DropdownMenuItem
                                            onSelect={(e) => {
                                                e.preventDefault();
                                                setTagIds([]);
                                            }}
                                        >
                                            <span className="flex-1">{t('tagAll')}</span>
                                        </DropdownMenuItem>
                                    )}
                                    {tagOptions.map(({ tag, count }) => {
                                        const isSelected = tagIds.includes(tag.id);
                                        return (
                                            <DropdownMenuItem
                                                key={tag.id}
                                                onSelect={(e) => {
                                                    e.preventDefault();
                                                    toggleTag(tag.id);
                                                }}
                                            >
                                                <span
                                                    className="size-2.5 shrink-0 rounded-full"
                                                    style={{ backgroundColor: tag.color }}
                                                />
                                                <span className="flex-1 truncate">{tag.name}</span>
                                                <span className="text-xs tabular-nums text-muted-foreground">{count}</span>
                                                {isSelected && <CheckIcon className="size-4 text-brand-dark" />}
                                            </DropdownMenuItem>
                                        );
                                    })}
                                </FilterMenu>
                            )}
                            <FilterMenu
                                Icon={ChevronUpDownIcon}
                                current={t(SORT_LABEL_KEY[sort])}
                                active={sort !== 'newest'}
                                srLabel={t('sortLabel')}
                                hideChevron
                            >
                                <DropdownMenuLabel>{t('sortLabel')}</DropdownMenuLabel>
                                <DropdownMenuSeparator />
                                {SORT_KEYS.map((key) => (
                                    <MenuChoice
                                        key={key}
                                        label={t(SORT_LABEL_KEY[key])}
                                        active={sort === key}
                                        onSelect={() => setSort(key)}
                                    />
                                ))}
                            </FilterMenu>
                            {facets && facets.orphaned > 0 && (
                                <button
                                    type="button"
                                    onClick={() => setOrphaned((o) => !o)}
                                    aria-pressed={orphaned}
                                    title={t('unlinkedHint')}
                                    className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium ring-1 transition ${
                                        orphaned
                                            ? 'bg-brand-light text-brand-dark ring-brand-light'
                                            : 'bg-muted text-muted-foreground ring-border hover:text-foreground'
                                    }`}
                                >
                                    <LinkSlashIcon className="size-3.5" />
                                    {t('unlinked')}
                                    <span className="tabular-nums">{facets.orphaned}</span>
                                </button>
                            )}
                            {filtersActive && (
                                <button
                                    type="button"
                                    onClick={clearFilters}
                                    className="rounded-full px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition hover:text-foreground"
                                >
                                    {t('clearFilters')}
                                </button>
                            )}
                            <div className="inline-flex shrink-0 items-center gap-0.5 rounded-full bg-muted p-0.5 ring-1 ring-border">
                                <ViewButton
                                    Icon={Squares2X2Icon}
                                    label={t('viewGrid')}
                                    active={view === 'grid'}
                                    onClick={() => setView('grid')}
                                />
                                <ViewButton
                                    Icon={Bars3Icon}
                                    label={t('viewList')}
                                    active={view === 'list'}
                                    onClick={() => setView('list')}
                                />
                            </div>
                        </div>

                        <div className="flex items-center gap-2">
                            <div className="relative min-w-0 flex-1 lg:flex-none">
                                <input
                                    type="text"
                                    value={query}
                                    onChange={(e) => setQuery(e.target.value)}
                                    placeholder={t('searchPlaceholder')}
                                    aria-label={t('searchPlaceholder')}
                                    className="w-full rounded-full bg-muted px-4 py-2 pr-9 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand lg:w-64"
                                />
                                <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                            </div>
                        </div>
                    </div>

                    <RecordsRenderView<Attachment>
                        data={items}
                        columns={columns}
                        renderCard={(item) => (
                            <FileCard
                                attachment={item}
                                kind={classifyKind(item.contentType, item.fileName)}
                                locale={locale}
                                reduce={reduce}
                                t={t}
                                selected={selectedIds.has(item.id)}
                                selectionActive={selectedIds.size > 0}
                                onToggleSelect={() => toggleSelect(item.id)}
                                onOpen={() => setDetailFile(item)}
                                onDelete={() => setDeleting(item)}
                            />
                        )}
                        renderAvatar={(item) => (
                            <FileGlyph attachment={item} kind={classifyKind(item.contentType, item.fileName)} />
                        )}
                        onRowClick={(item) => setDetailFile(item)}
                        displayMode={view === 'list' ? 'table' : 'grid'}
                        selectedIds={selectedIds}
                        onSelectedIdsChange={(ids) => setSelectedIds(ids as Set<number>)}
                        entityLabel={t('entityLabel')}
                        selectionActions={selectionActions}
                        loading={loading}
                        filtersActive={filtersActive}
                        onClearFilters={clearFilters}
                        pagination={{
                            page,
                            pageSize: size,
                            total,
                            onPageChange: setPage,
                            onPageSizeChange: setSize,
                        }}
                    />
                </>
            )}

            <Dialog open={!!deleting} onOpenChange={(open) => !open && setDeleting(null)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('deleteTitle')}</DialogTitle>
                        <DialogDescription>{t('deleteBody', { name: deleting?.fileName ?? '' })}</DialogDescription>
                    </DialogHeader>
                    {deleting && (
                        <div className="flex items-center gap-3 rounded-xl bg-muted px-4 py-3 ring-1 ring-border">
                            <FileGlyph attachment={deleting} kind={classifyKind(deleting.contentType, deleting.fileName)} />
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium text-foreground">{deleting.fileName}</p>
                                <p className="truncate text-xs tabular-nums text-muted-foreground">
                                    {formatFileSize(deleting.size)}
                                </p>
                            </div>
                        </div>
                    )}
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={busy}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="button" variant="destructive" onClick={handleDelete} disabled={busy}>
                            {busy ? <Loader2Icon className="size-4 animate-spin" /> : t('confirmDelete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            <Dialog open={bulkDeleting} onOpenChange={(open) => !open && !bulkBusy && setBulkDeleting(false)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('bulkDeleteTitle')}</DialogTitle>
                        <DialogDescription>{t('bulkDeleteBody', { count: selectedIds.size })}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={bulkBusy}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="button" variant="destructive" onClick={bulkDelete} disabled={bulkBusy}>
                            {bulkBusy ? <Loader2Icon className="size-4 animate-spin" /> : t('confirmDelete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            <FileDetailSheet
                attachment={detailFile}
                allTags={allTags}
                onOpenChange={(open) => {
                    if (!open) setDetailFile(null);
                }}
                onSelect={(a) => setDetailFile(a)}
                onDelete={(a) => {
                    setDetailFile(null);
                    setDeleting(a);
                }}
                onTagsChanged={() => {
                    if (detailFile) {
                        getAttachment(detailFile.id)
                            .then(setDetailFile)
                            .catch(() => {});
                    }
                    reload();
                    loadFacets();
                }}
            />
        </div>
    );
}

// rendered inside RecordsRenderView's grid wrapper (which supplies the key, exit
// animation, and selection outline), so this returns the card body — not a list item.
function FileCard({
    attachment,
    kind,
    locale,
    reduce,
    t,
    selected,
    selectionActive,
    onToggleSelect,
    onOpen,
    onDelete,
}: {
    attachment: Attachment;
    kind: FileKind;
    locale: string;
    reduce: boolean;
    t: T;
    selected: boolean;
    selectionActive: boolean;
    onToggleSelect: () => void;
    onOpen: () => void;
    onDelete: () => void;
}) {
    const Icon = KIND_ICON[kind];
    const isImage = kind === 'image';

    return (
        <motion.div
            whileHover={reduce ? undefined : { y: -3 }}
            transition={{ duration: 0.2, ease: EASE_OUT }}
            className="group flex h-full flex-col overflow-hidden rounded-2xl bg-card ring-1 ring-border transition-shadow duration-200 hover:shadow-lg"
        >
            <div className="flex items-center gap-2 px-3 py-2.5">
                <span className="relative inline-flex size-4 shrink-0 items-center justify-center">
                    <Icon
                        className={`size-4 text-muted-foreground transition-opacity ${
                            selected || selectionActive ? 'opacity-0' : 'opacity-100 group-hover:opacity-0'
                        }`}
                    />
                    <span
                        className={`absolute inset-0 inline-flex items-center justify-center transition-opacity ${
                            selected || selectionActive ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                        }`}
                    >
                        <Checkbox
                            checked={selected}
                            onCheckedChange={() => onToggleSelect()}
                            aria-label={t('selectAria', { name: attachment.fileName })}
                            className="size-4"
                        />
                    </span>
                </span>
                <button
                    type="button"
                    onClick={onOpen}
                    title={attachment.fileName}
                    className="min-w-0 flex-1 cursor-pointer truncate text-left text-sm font-medium text-foreground transition-colors hover:text-brand"
                >
                    {attachment.fileName}
                </button>
                <FileActionsMenu attachment={attachment} t={t} onDelete={onDelete} />
            </div>

            <button
                type="button"
                onClick={onOpen}
                title={attachment.fileName}
                aria-label={attachment.fileName}
                className="relative block aspect-[4/3] w-full cursor-pointer border-t border-border bg-muted/50"
            >
                {isImage ? (
                    <img
                        src={attachment.url}
                        alt=""
                        loading="lazy"
                        className="size-full object-cover transition-transform duration-300 group-hover:scale-[1.02]"
                    />
                ) : (
                    <span className="absolute inset-0 flex items-center justify-center text-muted-foreground/60">
                        <Icon className="size-12" />
                    </span>
                )}
            </button>

            {attachment.tags && attachment.tags.length > 0 && (
                <div className="flex flex-wrap gap-1 px-3 pt-2">
                    <FileTagChips tags={attachment.tags} max={3} />
                </div>
            )}

            <div className="mt-auto flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground">
                <OwnerChip attachment={attachment} t={t} className="min-w-0 flex-1" />
                <span className="shrink-0">{formatDate(attachment.createdAt, locale)}</span>
            </div>
        </motion.div>
    );
}
