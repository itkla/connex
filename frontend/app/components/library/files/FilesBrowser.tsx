'use client';

// TODO: turn the file browser into a Google Drive-like experience with folders and dragging-dropping etc.
// in lieu of this, the filesbrowser does NOT use RecordsRenderView abstraction because it simply cannot support such complex functionality.

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    ArrowDownTrayIcon,
    ArrowTopRightOnSquareIcon,
    Bars3Icon,
    CheckIcon,
    ChevronDownIcon,
    ChevronUpDownIcon,
    EllipsisVerticalIcon,
    FolderOpenIcon,
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
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

import { deleteAttachment } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { deleteUploadedFile, formatDate, formatFileSize, parseMysqlDateTime } from '@/app/lib/utils';
import type { Attachment } from '@/app/lib/types';
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

import NoResults from '@/app/components/library/files/NoResults';
import FileActionsMenu from '@/app/components/library/files/FileActionsMenu';
import FileGlyph from '@/app/components/library/files/FileGlyph';
import OwnerChip from '@/app/components/library/files/OwnerChip';
import IconLink from '@/app/components/library/files/IconLink';
import ViewButton from '@/app/components/library/files/ViewButton';
import FilterMenu from '@/app/components/library/files/FilterMenu';
import MenuChoice from '@/app/components/library/files/MenuChoice';
import EmptyState from '@/app/components/library/files/EmptyState';

type Props = { attachments: Attachment[] };
type SortKey = 'newest' | 'oldest' | 'name' | 'largest';
type ViewMode = 'grid' | 'list';
type T = ReturnType<typeof useTranslations>;
type IconType = React.ComponentType<{ className?: string }>;

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

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

function timestamp(a: Attachment): number {
    const t = parseMysqlDateTime(a.createdAt);
    return Number.isNaN(t) ? 0 : t;
}

export default function FilesBrowser({ attachments }: Props) {
    const t = useTranslations('LibraryFiles');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;

    const [items, setItems] = useState<Attachment[]>(attachments);
    const [query, setQuery] = useState('');
    const [kind, setKind] = useState<FileKind | 'all'>('all');
    const [source, setSource] = useState<SourceType | 'all'>('all');
    const [sort, setSort] = useState<SortKey>('newest');
    const [view, setView] = useState<ViewMode>('grid');

    const [deleting, setDeleting] = useState<Attachment | null>(null);
    const [busy, setBusy] = useState(false);

    const kindById = useMemo(
        () => new Map(items.map((a) => [a.id, classifyKind(a.contentType, a.fileName)] as const)),
        [items],
    );

    const kindsPresent = useMemo(() => {
        const counts = new Map<FileKind, number>();
        for (const a of items) {
            const k = kindById.get(a.id)!;
            counts.set(k, (counts.get(k) ?? 0) + 1);
        }
        return FILE_KINDS.filter((k) => counts.has(k)).map((k) => ({ kind: k, count: counts.get(k)! }));
    }, [items, kindById]);

    const sourcesPresent = useMemo(() => {
        const counts = new Map<SourceType, number>();
        for (const a of items) {
            if ((SOURCE_TYPES as readonly string[]).includes(a.entityType)) {
                const s = a.entityType as SourceType;
                counts.set(s, (counts.get(s) ?? 0) + 1);
            }
        }
        return SOURCE_TYPES.filter((s) => counts.has(s)).map((s) => ({ source: s, count: counts.get(s)! }));
    }, [items]);

    const totalSize = useMemo(() => items.reduce((sum, a) => sum + (a.size ?? 0), 0), [items]);

    const visible = useMemo(() => {
        const q = query.trim().toLowerCase();
        let list = items;
        if (source !== 'all') list = list.filter((a) => a.entityType === source);
        if (kind !== 'all') list = list.filter((a) => kindById.get(a.id) === kind);
        if (q) {
            list = list.filter(
                (a) =>
                    a.fileName.toLowerCase().includes(q) ||
                    (a.entityLabel?.toLowerCase().includes(q) ?? false) ||
                    (a.uploadedByName?.toLowerCase().includes(q) ?? false) ||
                    (a.contentType?.toLowerCase().includes(q) ?? false),
            );
        }
        return [...list].sort((a, b) => {
            switch (sort) {
                case 'name':
                    return a.fileName.localeCompare(b.fileName);
                case 'oldest':
                    return timestamp(a) - timestamp(b);
                case 'largest':
                    return (b.size ?? 0) - (a.size ?? 0);
                case 'newest':
                default:
                    return timestamp(b) - timestamp(a);
            }
        });
    }, [items, query, kind, source, sort, kindById]);

    const hasFiles = items.length > 0;
    const filtersActive = query.trim() !== '' || kind !== 'all' || source !== 'all';

    const clearFilters = () => {
        setQuery('');
        setKind('all');
        setSource('all');
    };

    const handleDelete = async () => {
        if (!deleting) return;
        setBusy(true);
        try {
            await deleteAttachment(deleting.id);
            await deleteUploadedFile(deleting.url);
            setItems((prev) => prev.filter((a) => a.id !== deleting.id));
            toastSuccess(t('toastDeleted'));
            setDeleting(null);
        } catch {
            toastError(t('toastDeleteFailed'));
        } finally {
            setBusy(false);
        }
    };

    const typeLabel = kind === 'all' ? t('typeAll') : t(KIND_LABEL_KEY[kind]);
    const sourceLabel = source === 'all' ? t('sourceAll') : t(SOURCE_LABEL_KEY[source]);

    return (
        <div className="space-y-8">
            <header className="flex flex-wrap items-end justify-between gap-4">
                <div>
                    <h1 className="text-4xl font-extrabold tracking-tight">{t('title')}</h1>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t('subtitle')}</p>
                </div>
                {hasFiles && (
                    <div className="text-right tabular-nums">
                        <div className="text-sm font-medium text-foreground">{t('count', { count: items.length })}</div>
                        <div className="text-xs text-muted-foreground">
                            {t('totalSize', { size: formatFileSize(totalSize) })}
                        </div>
                    </div>
                )}
            </header>

            {!hasFiles ? (
                <EmptyState t={t} />
            ) : (
                <>
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <div className="flex flex-wrap items-center gap-2">
                            {kindsPresent.length > 1 && (
                                <FilterMenu current={typeLabel} active={kind !== 'all'} srLabel={t('typeLabel')}>
                                    <DropdownMenuLabel>{t('typeLabel')}</DropdownMenuLabel>
                                    <DropdownMenuSeparator />
                                    <MenuChoice label={t('typeAll')} active={kind === 'all'} onSelect={() => setKind('all')} />
                                    {kindsPresent.map(({ kind: k, count }) => (
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
                            {sourcesPresent.length > 1 && (
                                <FilterMenu current={sourceLabel} active={source !== 'all'} srLabel={t('sourceLabel')}>
                                    <DropdownMenuLabel>{t('sourceLabel')}</DropdownMenuLabel>
                                    <DropdownMenuSeparator />
                                    <MenuChoice
                                        label={t('sourceAll')}
                                        active={source === 'all'}
                                        onSelect={() => setSource('all')}
                                    />
                                    {sourcesPresent.map(({ source: s, count }) => {
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

                    {visible.length === 0 ? (
                        <NoResults t={t} onClear={clearFilters} />
                    ) : view === 'grid' ? (
                        <ul className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-4">
                            <AnimatePresence initial={false} mode="popLayout">
                                {visible.map((a) => (
                                    <FileCard
                                        key={a.id}
                                        attachment={a}
                                        kind={kindById.get(a.id)!}
                                        locale={locale}
                                        reduce={reduce}
                                        t={t}
                                        onDelete={() => setDeleting(a)}
                                    />
                                ))}
                            </AnimatePresence>
                        </ul>
                    ) : (
                        <ul className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                            <AnimatePresence initial={false} mode="popLayout">
                                {visible.map((a) => (
                                    <FileRow
                                        key={a.id}
                                        attachment={a}
                                        kind={kindById.get(a.id)!}
                                        locale={locale}
                                        reduce={reduce}
                                        t={t}
                                        onDelete={() => setDeleting(a)}
                                    />
                                ))}
                            </AnimatePresence>
                        </ul>
                    )}
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
        </div>
    );
}

// TODO: move to separate component
function FileCard({
    attachment,
    kind,
    locale,
    reduce,
    t,
    onDelete,
}: {
    attachment: Attachment;
    kind: FileKind;
    locale: string;
    reduce: boolean;
    t: T;
    onDelete: () => void;
}) {
    const Icon = KIND_ICON[kind];
    const isImage = kind === 'image';

    return (
        <motion.li
            layout={!reduce}
            initial={false}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.96 }}
            transition={{ duration: 0.18, ease: EASE_OUT }}
        >
            <motion.div
                whileHover={reduce ? undefined : { y: -3 }}
                transition={{ duration: 0.2, ease: EASE_OUT }}
                className="group flex flex-col overflow-hidden rounded-2xl ring-1 ring-border bg-card transition-shadow duration-200 hover:shadow-lg"
            >
                <div className="flex items-center gap-2 px-3 py-2.5">
                    <Icon className="size-4 shrink-0 text-muted-foreground" />
                    <a
                        href={attachment.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        title={attachment.fileName}
                        className="min-w-0 flex-1 truncate text-sm font-medium text-foreground transition-colors hover:text-brand"
                    >
                        {attachment.fileName}
                    </a>
                    <FileActionsMenu attachment={attachment} t={t} onDelete={onDelete} />
                </div>

                <a
                    href={attachment.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={attachment.fileName}
                    className="relative block aspect-[4/3] border-t border-border bg-muted/50"
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
                </a>

                <div className="flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground">
                    <OwnerChip attachment={attachment} t={t} className="min-w-0 flex-1" />
                    <span className="shrink-0">{formatDate(attachment.createdAt, locale)}</span>
                </div>
            </motion.div>
        </motion.li>
    );
}

// TODO: move to separate component
function FileRow({
    attachment,
    kind,
    locale,
    reduce,
    t,
    onDelete,
}: {
    attachment: Attachment;
    kind: FileKind;
    locale: string;
    reduce: boolean;
    t: T;
    onDelete: () => void;
}) {
    return (
        <motion.li
            layout={!reduce}
            initial={false}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.98 }}
            transition={{ duration: 0.18, ease: EASE_OUT }}
            className="group flex items-center gap-3 border-b border-border px-3 py-2.5 transition-colors last:border-b-0 hover:bg-muted/40"
        >
            <FileGlyph attachment={attachment} kind={kind} />
            <div className="min-w-0 flex-1">
                <a
                    href={attachment.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={attachment.fileName}
                    className="block truncate text-sm font-medium text-foreground transition-colors hover:text-brand"
                >
                    {attachment.fileName}
                </a>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">
                    {t('by', { name: attachment.uploadedByName || t('unknownUser') })}
                </p>
            </div>

            <OwnerChip attachment={attachment} t={t} className="hidden w-40 shrink-0 md:flex" />
            <span className="hidden w-28 shrink-0 text-right text-xs text-muted-foreground lg:block">
                {formatDate(attachment.createdAt, locale)}
            </span>
            <span className="hidden w-16 shrink-0 text-right text-xs tabular-nums text-muted-foreground sm:block">
                {formatFileSize(attachment.size)}
            </span>

            <div className="flex shrink-0 items-center gap-0.5">
                <IconLink href={attachment.url} label={t('open')} Icon={ArrowTopRightOnSquareIcon} openInNewTab />
                <IconLink href={attachment.url} label={t('download')} Icon={ArrowDownTrayIcon} download={attachment.fileName} />
                <button
                    type="button"
                    onClick={onDelete}
                    title={t('delete')}
                    aria-label={t('delete')}
                    className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                >
                    <TrashIcon className="size-4" />
                </button>
            </div>
        </motion.li>
    );
}