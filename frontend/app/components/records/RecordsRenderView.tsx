'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    ChevronDownIcon,
    ChevronUpIcon,
    ChevronUpDownIcon,
    EllipsisHorizontalIcon,
    InboxIcon,
    PencilSquareIcon,
    TrashIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import { Checkbox } from '@/components/ui/checkbox';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { toast } from 'sonner';
import { useTranslations } from 'next-intl';

import { copyToClipboard } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import { type ColumnDef, type CardCallbacks, type DisplayMode, type SelectionId } from './types';

type SortDirection = 'asc' | 'desc';
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const CHECKBOX_CLASS = 'size-[18px] border-neutral-300 data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-white data-[state=indeterminate]:border-brand data-[state=indeterminate]:bg-brand data-[state=indeterminate]:text-white';

interface Props<T extends { id: SelectionId; name?: string }> {
    data: T[];
    columns: ColumnDef<T>[];
    renderCard: (item: T, callbacks: CardCallbacks<T>) => ReactNode;
    renderAvatar?: (item: T) => ReactNode;
    detailPath?: (item: T) => string;
    onRowClick?: (item: T) => void;
    displayMode: DisplayMode;
    selectedIds: Set<SelectionId>;
    onSelectedIdsChange: (ids: Set<SelectionId>) => void;
    onQuickEdit?: (item: T) => void;
    onDelete?: (item: T) => void;
    gridClassName?: string;
    entityLabel: string;
    selectionActions?: ReactNode; // pass along the react node for the selection actions
}

export default function RecordsRenderView<T extends { id: SelectionId; name?: string }>({
    data,
    columns,
    renderCard,
    renderAvatar,
    detailPath,
    onRowClick,
    displayMode,
    selectedIds,
    onSelectedIdsChange,
    onQuickEdit,
    onDelete,
    gridClassName = 'grid gap-4 grid-cols-[repeat(auto-fill,minmax(min(100%,16rem),1fr))]',
    entityLabel,
    selectionActions,
}: Props<T>) {
    const router = useRouter();
    const t = useTranslations('RecordsRenderView');
    const reduce = useReducedMotion() ?? false;
    const [sortKey, setSortKey] = useState<string | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDirection>('asc');

    const sortedData = useMemo(() => {
        if (!sortKey) return data;
        const col = columns.find((c) => c.key === sortKey);
        if (!col?.getSortValue) return data;
        const dir = sortDirection === 'asc' ? 1 : -1;
        return [...data].sort((a, b) => {
            const av = col.getSortValue!(a);
            const bv = col.getSortValue!(b);
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir;
            return String(av).localeCompare(String(bv), undefined, { sensitivity: 'base' }) * dir;
        });
    }, [data, columns, sortKey, sortDirection]);

    const handleSort = (key: string) => {
        if (sortKey === key) {
            setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
        } else {
            setSortKey(key);
            setSortDirection('asc');
        }
    };

    const allSelected = sortedData.length > 0 && selectedIds.size === sortedData.length;
    const someSelected = selectedIds.size > 0 && !allSelected;

    const toggleAll = (checked: boolean) => {
        onSelectedIdsChange(checked ? new Set(sortedData.map((item) => item.id)) : new Set());
    };

    const toggleOne = (id: SelectionId, checked: boolean) => {
        const next = new Set(selectedIds);
        if (checked) next.add(id);
        else next.delete(id);
        onSelectedIdsChange(next);
    };

    const hasRowActions = !!(onQuickEdit || onDelete);

    const selectionBar = (
        <AnimatePresence>
            {selectionActions && selectedIds.size > 0 && (
                <motion.div
                    key="selection-bar"
                    initial={reduce ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.97 }}
                    animate={reduce ? { opacity: 1 } : { opacity: 1, y: 0, scale: 1 }}
                    exit={reduce ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.97 }}
                    transition={{ duration: 0.24, ease: EASE_OUT }}
                    className="pointer-events-none fixed inset-x-0 bottom-6 z-40 flex justify-center px-4"
                >
                    <div className="pointer-events-auto flex items-center gap-1.5 rounded-full bg-white py-1.5 pr-1.5 pl-2 shadow-[0_8px_30px_rgb(0_0_0/0.12)] ring-1 ring-black/10">
                        <span className="flex items-center gap-2 pr-1 pl-1">
                            <span className="flex h-6 min-w-6 items-center justify-center rounded-full bg-brand px-1.5 text-xs font-semibold tabular-nums text-white">
                                {selectedIds.size}
                            </span>
                            <span className="hidden text-sm text-neutral-600 sm:inline">{t('selectedLabel')}</span>
                        </span>
                        <span className="h-5 w-px shrink-0 bg-neutral-200" />
                        {selectionActions}
                        <span className="h-5 w-px shrink-0 bg-neutral-200" />
                        <button
                            type="button"
                            onClick={() => onSelectedIdsChange(new Set())}
                            aria-label={t('clearSelection')}
                            className="flex size-8 shrink-0 items-center justify-center rounded-full text-neutral-500 transition hover:bg-neutral-100 hover:text-neutral-800 active:scale-95"
                        >
                            <XMarkIcon className="size-4" strokeWidth={2} />
                        </button>
                    </div>
                </motion.div>
            )}
        </AnimatePresence>
    );

    if (sortedData.length === 0) {
        return (
            <>
                <div className="rounded-2xl bg-white px-6 py-16 text-center ring-1 ring-black/5">
                    <div className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-neutral-100 text-neutral-400">
                        <InboxIcon className="size-6" />
                    </div>
                    <p className="mx-auto mt-4 max-w-sm text-sm font-medium text-neutral-600">{t('emptyState')}</p>
                </div>
                {selectionBar}
            </>
        );
    }

    if (displayMode === 'grid') {
        return (
            <>
                <div className={gridClassName}>
                    <AnimatePresence initial={false}>
                        {sortedData.map((item) => (
                            <motion.div
                                key={item.id}
                                initial={false}
                                exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.96, transition: { duration: 0.18, ease: EASE_OUT } }}
                                className={cn(
                                    'rounded-2xl',
                                    selectedIds.has(item.id) && 'outline-2 outline-offset-2 outline-brand',
                                )}
                            >
                                {renderCard(item, { onQuickEdit, onDelete })}
                            </motion.div>
                        ))}
                    </AnimatePresence>
                </div>
                {selectionBar}
            </>
        );
    }

    return (
        <>
            <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5">
                <div className="overflow-x-auto">
                    <table className="w-full border-collapse text-left text-sm">
                        <thead>
                            <tr className="border-b border-neutral-200 bg-neutral-50/60">
                                <th className="w-12 px-4 py-2.5">
                                    <Checkbox
                                        checked={someSelected ? 'indeterminate' : allSelected}
                                        onCheckedChange={(checked) => toggleAll(checked === true)}
                                        aria-label={t('selectAllAria', { entityLabel })}
                                        className={CHECKBOX_CLASS}
                                    />
                                </th>
                                {renderAvatar && <th className="w-12 px-4 py-2.5" aria-hidden />}
                                {columns.map((col) => {
                                    const active = sortKey === col.key;
                                    const sortable = !!col.getSortValue;
                                    const Icon = active
                                        ? sortDirection === 'asc'
                                            ? ChevronUpIcon
                                            : ChevronDownIcon
                                        : ChevronUpDownIcon;
                                    return (
                                        <th
                                            key={col.key}
                                            aria-sort={
                                                sortable
                                                    ? active
                                                        ? sortDirection === 'asc'
                                                            ? 'ascending'
                                                            : 'descending'
                                                        : 'none'
                                                    : undefined
                                            }
                                            className={cn(
                                                'px-4 py-2.5 text-xs font-semibold tracking-wide whitespace-nowrap text-neutral-500 uppercase',
                                                col.widthClass,
                                            )}
                                        >
                                            {sortable ? (
                                                <button
                                                    type="button"
                                                    onClick={() => handleSort(col.key)}
                                                    className={cn(
                                                        'inline-flex items-center gap-1 transition-colors hover:text-neutral-800',
                                                        active && 'text-neutral-800',
                                                    )}
                                                >
                                                    {col.label}
                                                    <Icon className={cn('size-3.5', !active && 'opacity-40')} aria-hidden="true" />
                                                </button>
                                            ) : (
                                                <span>{col.label}</span>
                                            )}
                                        </th>
                                    );
                                })}
                                {hasRowActions && <th className="w-12 px-4 py-2.5" aria-hidden />}
                            </tr>
                        </thead>
                        <tbody>
                            {sortedData.map((item) => {
                                const isSelected = selectedIds.has(item.id);
                                const clickable = !!(onRowClick || detailPath);
                                return (
                                    <tr
                                        key={item.id}
                                        data-state={isSelected ? 'selected' : undefined}
                                        className={cn(
                                            'group border-b border-neutral-100 transition-colors last:border-b-0',
                                            clickable && 'cursor-pointer',
                                            isSelected ? 'bg-brand-light/40 hover:bg-brand-light/55' : 'hover:bg-neutral-50',
                                        )}
                                        onClick={() => {
                                            if (onRowClick) onRowClick(item);
                                            else if (detailPath) router.push(detailPath(item));
                                        }}
                                    >
                                        <td className="px-4 py-2.5" onClick={(e) => e.stopPropagation()}>
                                            <Checkbox
                                                checked={isSelected}
                                                onCheckedChange={(checked) => toggleOne(item.id, checked === true)}
                                                aria-label={t('selectItemAria', { name: item.name ?? entityLabel })}
                                                className={CHECKBOX_CLASS}
                                            />
                                        </td>
                                        {renderAvatar && <td className="px-4 py-2.5">{renderAvatar(item)}</td>}
                                        {columns.map((col) => {
                                            const content = col.render
                                                ? col.render(item)
                                                : (item as unknown as Record<string, ReactNode>)[col.key];
                                            if (col.copyable) {
                                                const { label, getValue } = col.copyable;
                                                return (
                                                    <td
                                                        key={col.key}
                                                        className="px-4 py-2.5 text-neutral-700 transition-colors hover:text-brand-dark"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            const v = getValue(item) ?? '';
                                                            if (copyToClipboard(v, label)) {
                                                                toast.success(t('copiedToast', { label }));
                                                            } else {
                                                                toast.error(t('copyFailedToast', { label: label.toLowerCase() }));
                                                            }
                                                        }}
                                                    >
                                                        {content}
                                                    </td>
                                                );
                                            }
                                            return (
                                                <td key={col.key} className="px-4 py-2.5 text-neutral-700">
                                                    {content}
                                                </td>
                                            );
                                        })}
                                        {hasRowActions && (
                                            <td className="px-2 py-2.5 text-right" onClick={(e) => e.stopPropagation()}>
                                                <RowActions
                                                    name={item.name ?? entityLabel}
                                                    onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                                    onDelete={onDelete ? () => onDelete(item) : undefined}
                                                    actionsAria={t('rowActionsAria', { name: item.name ?? entityLabel })}
                                                    quickEditLabel={t('quickEdit')}
                                                    deleteLabel={t('delete')}
                                                />
                                            </td>
                                        )}
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            </div>
            {selectionBar}
        </>
    );
}

function RowActions({
    onQuickEdit,
    onDelete,
    actionsAria,
    quickEditLabel,
    deleteLabel,
}: {
    name: string;
    onQuickEdit?: () => void;
    onDelete?: () => void;
    actionsAria: string;
    quickEditLabel: string;
    deleteLabel: string;
}) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={actionsAria}
                    className="flex size-7 items-center justify-center rounded-full text-neutral-400 opacity-0 transition hover:bg-neutral-200/70 hover:text-neutral-700 group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100"
                >
                    <EllipsisHorizontalIcon className="size-5" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40">
                {onQuickEdit && (
                    <DropdownMenuItem onSelect={() => onQuickEdit()}>
                        <PencilSquareIcon className="size-4 text-neutral-500" />
                        {quickEditLabel}
                    </DropdownMenuItem>
                )}
                {onQuickEdit && onDelete && <DropdownMenuSeparator />}
                {onDelete && (
                    <DropdownMenuItem
                        className="text-destructive hover:bg-red-500/10"
                        onSelect={() => onDelete()}
                    >
                        <TrashIcon className="size-4 text-destructive" />
                        {deleteLabel}
                    </DropdownMenuItem>
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}