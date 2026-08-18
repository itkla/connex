'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    SwatchIcon,
    BarsArrowDownIcon,
    PencilIcon,
    TrashIcon,
    TagIcon,
    EllipsisHorizontalIcon,
    ClipboardIcon,
    MagnifyingGlassIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';
import { Loader2Icon } from 'lucide-react';

import { SearchField, FilterBar, SortToggle, type FilterChipData } from '@/app/components/filters';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';

import { deleteTag } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { compareByColor, copyToClipboard, readableTextColor } from '@/app/lib/utils';
import type { Tag } from '@/app/lib/types';
import Rise from '@/app/components/motion/Rise';
import TagDialog from '@/app/components/library/tags/TagDialog';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { EmptyState } from '@/app/components/EmptyState';

type Props = { tags: Tag[] };
type SortKey = 'color' | 'name';
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function tilePresence(reduce: boolean) {
    return reduce
        ? {
              initial: { opacity: 0 },
              animate: { opacity: 1 },
              exit: { opacity: 0 },
              transition: { duration: 0.15 },
          }
        : {
              initial: { opacity: 0, scale: 0.9 },
              animate: { opacity: 1, scale: 1 },
              exit: { opacity: 0, scale: 0.9 },
              transition: { duration: 0.2, ease: EASE_OUT },
          };
}

export default function TagsBrowser({ tags: initialTags }: Props) {
    const t = useTranslations('ActivityLibraryTags');
    const tf = useTranslations('Filters');
    const reduce = useReducedMotion() ?? false;

    const [tags, setTags] = useState<Tag[]>(initialTags);
    const [query, setQuery] = useState('');
    const [sort, setSort] = useState<SortKey>('color');

    const [dialogOpen, setDialogOpen] = useState(false);
    const [dialogMode, setDialogMode] = useState<'create' | 'edit'>('create');
    const [editingTag, setEditingTag] = useState<Tag | null>(null);

    const [deletingTag, setDeletingTag] = useState<Tag | null>(null);
    const [deleting, setDeleting] = useState(false);

    const openCreate = () => {
        setDialogMode('create');
        setEditingTag(null);
        setDialogOpen(true);
    };

    const openEdit = (tag: Tag) => {
        setDialogMode('edit');
        setEditingTag(tag);
        setDialogOpen(true);
    };

    const handleSaved = (saved: Tag) => {
        setTags((prev) => {
            const exists = prev.some((tag) => tag.id === saved.id);
            return exists ? prev.map((tag) => (tag.id === saved.id ? saved : tag)) : [...prev, saved];
        });
    };

    const handleCopy = (tag: Tag) => {
        if (copyToClipboard(tag.color, 'color')) toastSuccess(t('toastHexCopied', { hex: tag.color }));
        else toastError(t('toastHexCopyFailed'));
    };

    const handleDelete = async () => {
        if (!deletingTag) return;
        setDeleting(true);
        try {
            await deleteTag(deletingTag.id);
            setTags((prev) => prev.filter((tag) => tag.id !== deletingTag.id));
            toastSuccess(t('toastDeleted', { name: deletingTag.name }));
            setDeletingTag(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedDelete'));
        } finally {
            setDeleting(false);
        }
    };

    const visible = useMemo(() => {
        const q = query.trim().toLowerCase();
        const filtered = q
            ? tags.filter((tag) => tag.name.toLowerCase().includes(q) || tag.color.toLowerCase().includes(q))
            : tags;
        const sorted = [...filtered].sort((a, b) =>
            sort === 'name'
                ? a.name.localeCompare(b.name)
                : compareByColor(a.color, b.color) || a.name.localeCompare(b.name),
        );
        return sorted;
    }, [tags, query, sort]);

    const hasTags = tags.length > 0;
    const noResults = hasTags && visible.length === 0;

    return (
        <PageShell tier="wide">
            <Rise>
                <PageHeader
                    title={t('title')}
                    description={t('subtitle')}
                    actions={
                        hasTags ? (
                            <Button
                                variant="brand"
                                aria-label={t('newAria')}
                                onClick={openCreate}
                            >
                                <PlusIcon strokeWidth={2.5} />
                                {t('newTag')}
                            </Button>
                        ) : undefined
                    }
                />
            </Rise>

            {hasTags && (
                <Rise delay={0.06}>
                <FilterBar
                    reduce={reduce}
                    chips={query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] as FilterChipData[] : []}
                    hasActiveFilters={query.trim() !== ''}
                    onClearAll={() => setQuery('')}
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
                        <div className="flex items-center gap-3">
                            <SortToggle
                                value={sort}
                                onChange={setSort}
                                options={[
                                    { value: 'color', label: t('sortColor'), icon: <SwatchIcon className="size-3.5" /> },
                                    { value: 'name', label: t('sortName'), icon: <BarsArrowDownIcon className="size-3.5" /> },
                                ]}
                            />
                            <span className="hidden text-xs tabular-nums text-muted-foreground sm:inline">
                                {t('count', { count: tags.length })}
                            </span>
                        </div>
                    }
                />
                </Rise>
            )}

            <Rise delay={0.12}>
            {!hasTags ? (
                <EmptyState
                    icon={TagIcon}
                    title={t('emptyTitle')}
                    body={t('emptyBody')}
                    action={
                        <Button onClick={openCreate} variant="brand">
                            <PlusIcon strokeWidth={2.5} />
                            {t('createFirst')}
                        </Button>
                    }
                />
            ) : noResults ? (
                <EmptyState
                    tone="muted"
                    icon={MagnifyingGlassIcon}
                    title={t('noResultsTitle')}
                    body={t('noResults', { query: query.trim() })}
                    action={
                        <Button variant="outline" onClick={() => setQuery('')}>
                            {tf('clearAll')}
                        </Button>
                    }
                />
            ) : (
                <ul className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
                    <AnimatePresence mode="popLayout" initial={false}>
                        {visible.map((tag) => (
                            <TagTile
                                key={tag.id}
                                tag={tag}
                                reduce={reduce}
                                onEdit={() => openEdit(tag)}
                                onCopy={() => handleCopy(tag)}
                                onDelete={() => setDeletingTag(tag)}
                                t={t}
                            />
                        ))}
                        {!query.trim() && (
                            <AddTile key="add-tile" reduce={reduce} label={t('newTag')} onClick={openCreate} />
                        )}
                    </AnimatePresence>
                </ul>
            )}
            </Rise>

            <TagDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                mode={dialogMode}
                tag={editingTag}
                onSaved={handleSaved}
            />

            <Dialog open={!!deletingTag} onOpenChange={(open) => !open && setDeletingTag(null)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('deleteTitle')}</DialogTitle>
                        <DialogDescription>
                            {t('deleteBody', { name: deletingTag?.name ?? '' })}
                        </DialogDescription>
                    </DialogHeader>
                    {deletingTag && (
                        <div className="flex items-center justify-center rounded-xl bg-muted px-4 py-5 ring-1 ring-border">
                            <span
                                className="inline-flex max-w-full items-center rounded-4xl px-3 py-1 text-sm font-medium"
                                style={{
                                    backgroundColor: deletingTag.color,
                                    color: readableTextColor(deletingTag.color),
                                }}
                            >
                                <span className="truncate">{deletingTag.name}</span>
                            </span>
                        </div>
                    )}
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={deleting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="button" variant="destructive" onClick={handleDelete} disabled={deleting}>
                            {deleting ? <Loader2Icon className="size-4 animate-spin" /> : t('confirmDelete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </PageShell>
    );
}

type TileProps = {
    tag: Tag;
    reduce: boolean;
    onEdit: () => void;
    onCopy: () => void;
    onDelete: () => void;
    t: ReturnType<typeof useTranslations>;
};

function TagTile({ tag, reduce, onEdit, onCopy, onDelete, t }: TileProps) {
    const ink = readableTextColor(tag.color);

    return (
        <motion.li layout={!reduce} {...tilePresence(reduce)}>
            <motion.div
                whileHover={reduce ? undefined : { y: -2 }}
                transition={{ duration: 0.2, ease: EASE_OUT }}
                className="group relative overflow-hidden rounded-2xl border border-border bg-card transition-shadow duration-200 hover:shadow-lg"
            >
                <motion.button
                    type="button"
                    onClick={onEdit}
                    whileTap={reduce ? undefined : { scale: 0.98 }}
                    aria-label={t('editAria', { name: tag.name })}
                    className="flex h-24 w-full items-end p-3 text-left"
                    style={{ backgroundColor: tag.color }}
                >
                    <span
                        className="line-clamp-2 text-sm font-semibold leading-tight break-words"
                        style={{ color: ink }}
                    >
                        {tag.name}
                    </span>
                </motion.button>

                <div className="flex items-center justify-between gap-1 px-3 py-2">
                    <button
                        type="button"
                        onClick={onCopy}
                        title={t('copyHex')}
                        className="font-mono text-[11px] uppercase tracking-wide text-muted-foreground transition hover:text-foreground"
                    >
                        {tag.color}
                    </button>
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                aria-label={t('actionsAria', { name: tag.name })}
                                className="flex size-6 shrink-0 items-center justify-center rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground"
                            >
                                <EllipsisHorizontalIcon className="size-4" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-40">
                            <DropdownMenuItem onSelect={onEdit}>
                                <PencilIcon className="size-4 text-muted-foreground" />
                                {t('edit')}
                            </DropdownMenuItem>
                            <DropdownMenuItem onSelect={onCopy}>
                                <ClipboardIcon className="size-4 text-muted-foreground" />
                                {t('copyHex')}
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                                className="text-destructive hover:bg-destructive/10"
                                onSelect={(e) => {
                                    e.preventDefault();
                                    onDelete();
                                }}
                            >
                                <TrashIcon className="size-4 text-destructive" />
                                {t('delete')}
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            </motion.div>
        </motion.li>
    );
}

function AddTile({
    reduce,
    label,
    onClick,
}: {
    reduce: boolean;
    label: string;
    onClick: () => void;
}) {
    return (
        <motion.li layout={!reduce} {...tilePresence(reduce)}>
            <motion.button
                type="button"
                onClick={onClick}
                whileHover={reduce ? undefined : { y: -2 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={{ duration: 0.2, ease: EASE_OUT }}
                className="flex h-full min-h-[8.5rem] w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-border text-muted-foreground transition-colors hover:border-brand hover:bg-brand-light/30 hover:text-brand-dark"
            >
                <PlusIcon className="size-5" strokeWidth={2.5} />
                <span className="text-sm font-medium">{label}</span>
            </motion.button>
        </motion.li>
    );
}
