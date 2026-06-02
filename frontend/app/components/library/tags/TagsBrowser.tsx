'use client';

import { useMemo, useState, type ComponentType } from 'react';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    MagnifyingGlassIcon,
    SwatchIcon,
    BarsArrowDownIcon,
    PencilIcon,
    TrashIcon,
    TagIcon,
    EllipsisHorizontalIcon,
    ClipboardIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';
import { Loader2Icon } from 'lucide-react';

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
import TagDialog from '@/app/components/library/tags/TagDialog';

type Props = { tags: Tag[] };
type SortKey = 'color' | 'name';
type IconType = ComponentType<{ className?: string }>;
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
        <div className="space-y-8">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                    <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                    <p className="mt-1 max-w-prose text-sm text-neutral-500">{t('subtitle')}</p>
                </div>
                {hasTags && (
                    <Button
                        className="bg-brand text-white hover:bg-brand-dark"
                        aria-label={t('newAria')}
                        onClick={openCreate}
                    >
                        <PlusIcon strokeWidth={2.5} />
                        {t('newTag')}
                    </Button>
                )}
            </div>

            {hasTags && (
                <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="flex items-center gap-3">
                        <div className="inline-flex items-center gap-0.5 rounded-full bg-neutral-100 p-0.5 ring-1 ring-black/5">
                            <SortButton
                                Icon={SwatchIcon}
                                label={t('sortColor')}
                                active={sort === 'color'}
                                onClick={() => setSort('color')}
                            />
                            <SortButton
                                Icon={BarsArrowDownIcon}
                                label={t('sortName')}
                                active={sort === 'name'}
                                onClick={() => setSort('name')}
                            />
                        </div>
                        <span className="hidden text-xs tabular-nums text-neutral-500 sm:inline">
                            {t('count', { count: tags.length })}
                        </span>
                    </div>

                    <div className="relative ml-auto w-full max-w-xs">
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
            )}

            {!hasTags ? (
                <EmptyState
                    title={t('emptyTitle')}
                    body={t('emptyBody')}
                    cta={t('createFirst')}
                    onCreate={openCreate}
                />
            ) : noResults ? (
                <div className="rounded-2xl bg-white px-6 py-20 text-center ring-1 ring-black/5">
                    <p className="text-sm text-neutral-500">{t('noResults', { query: query.trim() })}</p>
                </div>
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
                        <div className="flex items-center justify-center rounded-xl bg-neutral-50 px-4 py-5 ring-1 ring-black/5">
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
        </div>
    );
}

function SortButton({
    Icon,
    label,
    active,
    onClick,
}: {
    Icon: IconType;
    label: string;
    active: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-pressed={active}
            title={label}
            className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition ${
                active ? 'bg-white text-neutral-900 shadow-sm' : 'text-neutral-500 hover:text-neutral-800'
            }`}
        >
            <Icon className="size-3.5" />
            <span className="hidden sm:inline">{label}</span>
        </button>
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
                className="group relative overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 transition-shadow duration-200 hover:shadow-lg"
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
                        className="font-mono text-[11px] uppercase tracking-wide text-neutral-500 transition hover:text-neutral-800"
                    >
                        {tag.color}
                    </button>
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                aria-label={t('actionsAria', { name: tag.name })}
                                className="flex size-6 shrink-0 items-center justify-center rounded-full text-neutral-500 transition hover:bg-neutral-100 hover:text-neutral-800 aria-expanded:bg-neutral-100 aria-expanded:text-neutral-800"
                            >
                                <EllipsisHorizontalIcon className="size-4" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-40">
                            <DropdownMenuItem onSelect={onEdit}>
                                <PencilIcon className="size-4 text-neutral-500" />
                                {t('edit')}
                            </DropdownMenuItem>
                            <DropdownMenuItem onSelect={onCopy}>
                                <ClipboardIcon className="size-4 text-neutral-500" />
                                {t('copyHex')}
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                                className="text-destructive hover:bg-red-500/10"
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
                className="flex h-full min-h-[8.5rem] w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-neutral-200 text-neutral-500 transition-colors hover:border-brand hover:bg-brand-light/30 hover:text-brand-dark"
            >
                <PlusIcon className="size-5" strokeWidth={2.5} />
                <span className="text-sm font-medium">{label}</span>
            </motion.button>
        </motion.li>
    );
}

function EmptyState({
    title,
    body,
    cta,
    onCreate,
}: {
    title: string;
    body: string;
    cta: string;
    onCreate: () => void;
}) {
    return (
        <div className="rounded-2xl bg-white px-6 py-20 text-center ring-1 ring-black/5">
            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <TagIcon className="size-7" />
            </div>
            <h2 className="mt-5 text-lg font-semibold text-neutral-900">{title}</h2>
            <p className="mx-auto mt-1.5 max-w-sm text-sm text-neutral-500">{body}</p>
            <Button onClick={onCreate} className="mt-6 bg-brand text-white hover:bg-brand-dark">
                <PlusIcon strokeWidth={2.5} />
                {cta}
            </Button>
        </div>
    );
}
