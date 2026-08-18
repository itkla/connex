'use client';

/* eslint-disable @next/next/no-img-element */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import {
    ArrowDownTrayIcon,
    ArrowTopRightOnSquareIcon,
    Bars3Icon,
    CheckIcon,
    EllipsisVerticalIcon,
    FolderOpenIcon,
    LinkSlashIcon,
    Squares2X2Icon,
    TagIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { Checkbox } from '@/components/ui/checkbox';
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
import { formatDate, formatFileSize } from '@/app/lib/utils';
import type { Attachment, AttachmentFacets, AttachmentsPageParams, PageParams, Tag } from '@/app/lib/types';
import {
    classifyKind,
    FILE_KINDS,
    KIND_ICON,
    KIND_LABEL_KEY,
    SOURCE_TYPES,
    type FileKind,
    type SourceType,
} from '@/app/components/library/files/fileMeta';

import { parseDeepLinkId } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import Rise from '@/app/components/motion/Rise';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import { type ColumnDef } from '@/app/components/records/types';
import {
    SearchField,
    FilterBar,
    MultiSelectFilter,
    RadioFilter,
    pillClass,
    type FilterChipData,
} from '@/app/components/filters';
import { SegmentedControl } from '@/components/ui/segmented-control';
import { IconButton } from '@/components/ui/icon-button';
import FileActionsMenu from '@/app/components/library/files/FileActionsMenu';
import FileGlyph from '@/app/components/library/files/FileGlyph';
import OwnerChip from '@/app/components/library/files/OwnerChip';
import IconLink from '@/app/components/library/files/IconLink';
import { EmptyState } from '@/app/components/EmptyState';
import FileDetailSheet from '@/app/components/library/files/FileDetailSheet';
import FileTagChips from '@/app/components/library/files/FileTagChips';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';

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
    const tf = useTranslations('Filters');
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
    >(getAttachmentsPage, extraParams, {
        defaultSize: PAGE_SIZE,
        seedQuery: searchParams.get('q') ?? undefined,
        seedPage: searchParams.get('page') ?? undefined,
        seedSize: searchParams.get('size') ?? undefined,
    });

    const [deepLinkSettled, setDeepLinkSettled] = useState(
        () => parseDeepLinkId(searchParams.get('file')) === null,
    );
    useEffect(() => {
        const fileId = parseDeepLinkId(searchParams.get('file'));
        if (fileId === null) return;
        getAttachment(fileId)
            .then(setDetailFile)
            .catch(() => {})
            .finally(() => setDeepLinkSettled(true));
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

    useOwnedUrlParams({
        q: query || undefined,
        kind: kind !== 'all' ? kind : undefined,
        source: source !== 'all' ? source : undefined,
        sort: sort !== 'newest' ? sort : undefined,
        tags: tagIds.length ? tagIds.join(',') : undefined,
        orphaned: orphaned ? '1' : undefined,
        page: page > 1 ? String(page) : undefined,
        size: size !== PAGE_SIZE ? String(size) : undefined,
    });
    useOwnedUrlParams({ file: detailFile ? String(detailFile.id) : undefined }, deepLinkSettled);

    // selection is scoped to the loaded page; drop it whenever the result set changes
    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => setSelectedIds(new Set()), [items]);

    const kindOptions = useMemo(() => {
        const counts = new Map((facets?.kinds ?? []).map((f) => [f.key, f.count]));
        return FILE_KINDS.flatMap((kind) => {
            const count = counts.get(kind);
            return count === undefined ? [] : [{ kind, count }];
        });
    }, [facets]);

    const sourceOptions = useMemo(() => {
        const counts = new Map((facets?.sources ?? []).map((f) => [f.key, f.count]));
        return SOURCE_TYPES.flatMap((source) => {
            const count = counts.get(source);
            return count === undefined ? [] : [{ source, count }];
        });
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
    const libraryEmptyState = (
        <EmptyState
            icon={FolderOpenIcon}
            title={t('emptyTitle')}
            body={t('emptyBody')}
            action={
                <Button asChild variant="brand">
                    <Link href="/records/companies">{t('emptyCta')}</Link>
                </Button>
            }
        />
    );
    const filtersActive =
        query.trim() !== '' || kind !== 'all' || source !== 'all' || tagIds.length > 0 || orphaned;

    const clearFilters = () => {
        setQuery('');
        setKind('all');
        setSource('all');
        setTagIds([]);
        setOrphaned(false);
    };

    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...(kind !== 'all' ? [{ id: 'kind', label: t(KIND_LABEL_KEY[kind]), onRemove: () => setKind('all') }] : []),
        ...(source !== 'all' ? [{ id: 'source', label: t(SOURCE_LABEL_KEY[source]), onRemove: () => setSource('all') }] : []),
        ...tagIds.map((id) => ({ id: `tag-${id}`, label: tagById.get(id)?.name ?? String(id), onRemove: () => toggleTag(id) })),
        ...(orphaned ? [{ id: 'orphaned', label: t('unlinked'), onRemove: () => setOrphaned(false) }] : []),
    ];

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
                selectedAttachments.map((a) => deleteAttachment(a.id)),
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
                        <IconButton
                            variant="ghost"
                            size="icon-toolbar"
                            label={t('delete')}
                            onClick={() => setDeleting(a)}
                            className="text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                        >
                            <TrashIcon className="size-4" />
                        </IconButton>
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
                        <Button variant="outline" size="toolbar" menu disabled={bulkBusy}>
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
                    <IconButton
                        variant="outline"
                        size="icon-toolbar"
                        disabled={bulkBusy}
                        label={t('actionsAria', { name: t('selectedCount', { count: selectedIds.size }) })}
                    >
                        {bulkBusy ? (
                            <Loader2Icon className="size-4 animate-spin" />
                        ) : (
                            <EllipsisVerticalIcon className="size-4" />
                        )}
                    </IconButton>
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
        <PageShell tier="wide">
            <Rise>
                <div className="flex flex-col gap-3">
                    <PageHeader title={t('title')} description={t('subtitle')} />
                    {facets && facets.total > 0 ? (
                        <p className="text-xs tabular-nums text-muted-foreground">
                            {t('countAndSize', {
                                count: facets.total,
                                size: formatFileSize(facets.totalSize),
                            })}
                        </p>
                    ) : null}
                </div>
            </Rise>

            {isEmptyLibrary ? (
                <Rise delay={0.06}>{libraryEmptyState}</Rise>
            ) : (
                <>
                    <Rise delay={0.06}>
                    <FilterBar
                        reduce={reduce}
                        chips={chips}
                        hasActiveFilters={filtersActive}
                        onClearAll={clearFilters}
                        clearAllLabel={tf('clearAll')}
                        search={
                            <SearchField
                                value={query}
                                onChange={setQuery}
                                onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={t('searchPlaceholder')}
                                clearAria={tf('clearSearchAria')}
                            />
                        }
                        trailing={
                            <div className="flex items-center gap-1.5">
                                <RadioFilter
                                    label={t('sortLabel')}
                                    ariaLabel={t('sortLabel')}
                                    value={sort}
                                    onValueChange={(v) => setSort(v as SortKey)}
                                    options={SORT_KEYS.map((key) => ({ value: key, label: t(SORT_LABEL_KEY[key]) }))}
                                />
                                <SegmentedControl
                                    ariaLabel={t('viewGrid')}
                                    value={view}
                                    onChange={setView}
                                    options={[
                                        { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('viewGrid') },
                                        { value: 'list', icon: <Bars3Icon className="size-4" />, ariaLabel: t('viewList') },
                                    ]}
                                />
                            </div>
                        }
                    >
                        {kindOptions.length > 1 && (
                            <RadioFilter
                                label={t('typeLabel')}
                                ariaLabel={t('typeLabel')}
                                value={kind}
                                onValueChange={(v) => setKind(v as FileKind | 'all')}
                                options={[
                                    { value: 'all', label: t('typeAll') },
                                    ...kindOptions.map(({ kind: k, count }) => ({ value: k, label: t(KIND_LABEL_KEY[k]), count })),
                                ]}
                            />
                        )}
                        {sourceOptions.length > 1 && (
                            <RadioFilter
                                label={t('sourceLabel')}
                                ariaLabel={t('sourceLabel')}
                                value={source}
                                onValueChange={(v) => setSource(v as SourceType | 'all')}
                                options={[
                                    { value: 'all', label: t('sourceAll') },
                                    ...sourceOptions.map(({ source: s, count }) => ({ value: s, label: t(SOURCE_LABEL_KEY[s]), count })),
                                ]}
                            />
                        )}
                        {tagOptions.length > 0 && (
                            <MultiSelectFilter
                                label={t('tagLabel')}
                                ariaLabel={t('tagLabel')}
                                options={tagOptions.map(({ tag, count }) => ({ value: String(tag.id), label: tag.name, total: count }))}
                                selected={new Set(tagIds.map(String))}
                                onToggle={(v) => toggleTag(Number(v))}
                                onClear={() => setTagIds([])}
                                clearLabel={tf('clear')}
                                scroll
                            />
                        )}
                        {facets && facets.orphaned > 0 && (
                            <button
                                type="button"
                                onClick={() => setOrphaned((o) => !o)}
                                aria-pressed={orphaned}
                                title={t('unlinkedHint')}
                                className={pillClass(orphaned)}
                            >
                                <LinkSlashIcon className="size-3.5" />
                                {t('unlinked')}
                                <span className="tabular-nums">{facets.orphaned}</span>
                            </button>
                        )}
                    </FilterBar>
                    </Rise>

                    <Rise delay={0.12}>
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
                        emptyState={libraryEmptyState}
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
                    </Rise>
                </>
            )}

            <DeleteRecordDialog
                open={!!deleting}
                onOpenChange={(open) => !open && setDeleting(null)}
                selectedIds={new Set(deleting ? [deleting.id] : [])}
                selectedItems={deleting ? [deleting] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(item) => item.fileName}
                details={deleting ? (
                    <div className="flex items-center gap-3 rounded-xl bg-muted px-4 py-3 ring-1 ring-border">
                        <FileGlyph attachment={deleting} kind={classifyKind(deleting.contentType, deleting.fileName)} />
                        <div className="min-w-0">
                            <p className="truncate text-sm font-medium text-foreground">{deleting.fileName}</p>
                            <p className="truncate text-xs tabular-nums text-muted-foreground">
                                {formatFileSize(deleting.size)}
                            </p>
                        </div>
                    </div>
                ) : undefined}
                isDeleting={busy}
                confirmDelete={handleDelete}
            />

            <DeleteRecordDialog
                open={bulkDeleting}
                onOpenChange={(open) => !open && !bulkBusy && setBulkDeleting(false)}
                selectedIds={selectedIds}
                selectedItems={[]}
                entityLabel={t('entityLabel')}
                isDeleting={bulkBusy}
                confirmDelete={bulkDelete}
            />

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
        </PageShell>
    );
}

/**
 * Renders a single file card inside RecordsRenderView's grid wrapper, which supplies
 * the key, exit animation, and selection outline, so this returns the card body rather
 * than a list item.
 */
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
            className="group flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card transition-shadow duration-200 hover:shadow-lg"
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
