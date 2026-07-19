'use client';

import { useCallback, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    ChevronDownIcon,
    ChevronUpIcon,
    ChevronUpDownIcon,
    InboxIcon,
    PencilSquareIcon,
    TrashIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationLink,
    PaginationPrevious,
    PaginationNext,
    PaginationEllipsis,
} from '@/components/ui/pagination';
import { toast } from 'sonner';
import { useTranslations } from 'next-intl';

import { copyToClipboard } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import { useDragScroll } from '@/app/hooks/useDragScroll';
import type { RowDensity } from '@/app/hooks/useRecordDensity';
import {
    RecordActionMenuTrigger,
    RecordActionsTriggerButton,
    RecordContextMenu,
    type RecordMenuModel,
} from './RecordActionMenu';
import type { ActiveRecordRef } from '@/app/lib/actions/types';
import { type ColumnDef, type CardCallbacks, type DisplayMode, type SelectionId } from './types';

type SortDirection = 'asc' | 'desc';
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const PAGE_SIZES = [10, 25, 50, 100];

function pageList(current: number, total: number): (number | 'gap')[] {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    const out: (number | 'gap')[] = [1];
    const start = Math.max(2, current - 1);
    const end = Math.min(total - 1, current + 1);
    if (start > 2) out.push('gap');
    for (let i = start; i <= end; i++) out.push(i);
    if (end < total - 1) out.push('gap');
    out.push(total);
    return out;
}
const CHECKBOX_CLASS = 'size-[18px] border-border data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-brand-foreground data-[state=indeterminate]:border-brand data-[state=indeterminate]:bg-brand data-[state=indeterminate]:text-brand-foreground';

function isSortableColumn<T>(column: ColumnDef<T>): boolean {
    return column.sortable !== false && !!column.getSortValue;
}

interface Props<T extends { id: SelectionId; name?: string }> {
    data: T[];
    columns: ColumnDef<T>[];
    renderCard: (item: T, callbacks: CardCallbacks<T>) => ReactNode;
    renderAvatar?: (item: T) => ReactNode;
    detailPath?: (item: T) => string;
    onRowClick?: (item: T) => void;
    activeId?: SelectionId | null;
    displayMode: DisplayMode;
    density?: RowDensity;
    selectedIds: Set<SelectionId>;
    onSelectedIdsChange: (ids: Set<SelectionId>) => void;
    onQuickEdit?: (item: T) => void;
    onDelete?: (item: T) => void;
    recordRef?: (item: T) => ActiveRecordRef | null;
    gridClassName?: string;
    entityLabel: string;
    selectionActions?: ReactNode; // pass along the react node for the selection actions
    pagination?: {
        page: number;
        pageSize: number;
        total: number;
        onPageChange: (page: number) => void;
        onPageSizeChange: (size: number) => void;
    };
    sortState?: { key: string | null; direction: SortDirection; onSortChange: (key: string) => void };
    sortOptions?: { key: string; label: string }[];
    emptyState?: ReactNode;
    filtersActive?: boolean;
    onClearFilters?: () => void;
    loading?: boolean;
    addColumnSlot?: ReactNode;
}

export default function RecordsRenderView<T extends { id: SelectionId; name?: string }>({
    data,
    columns,
    renderCard,
    renderAvatar,
    detailPath,
    onRowClick,
    activeId,
    displayMode,
    density = 'comfortable',
    selectedIds,
    onSelectedIdsChange,
    onQuickEdit,
    onDelete,
    recordRef,
    gridClassName = 'grid gap-4 grid-cols-[repeat(auto-fill,minmax(min(100%,16rem),1fr))]',
    entityLabel,
    selectionActions,
    pagination,
    sortState,
    sortOptions,
    emptyState,
    filtersActive = false,
    onClearFilters,
    loading = false,
    addColumnSlot,
}: Props<T>) {
    const router = useRouter();
    const t = useTranslations('RecordsRenderView');
    const reduce = useReducedMotion() ?? false;
    const { ref: scrollRef, edges } = useDragScroll<HTMLDivElement>({ leftDragSelector: 'thead' });
    const [sortKey, setSortKey] = useState<string | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDirection>('asc');
    const [pageSize, setPageSize] = useState(25);
    const [page, setPage] = useState(1);

    const server = !!pagination;
    const controlled = !!sortState;
    const activeSortKey = controlled ? sortState!.key : sortKey;
    const activeSortDirection = controlled ? sortState!.direction : sortDirection;

    const sortedData = useMemo(() => {
        if (server || !activeSortKey) return data;
        const col = columns.find((c) => c.key === activeSortKey);
        if (col?.sortable === false) return data;
        if (!col?.getSortValue) return data;
        const dir = activeSortDirection === 'asc' ? 1 : -1;
        return [...data].sort((a, b) => {
            const av = col.getSortValue!(a);
            const bv = col.getSortValue!(b);
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir;
            return String(av).localeCompare(String(bv), undefined, { sensitivity: 'base' }) * dir;
        });
    }, [server, data, columns, activeSortKey, activeSortDirection]);

    const total = server ? pagination!.total : sortedData.length;
    const effectivePageSize = server ? pagination!.pageSize : pageSize;
    const pageCount = Math.max(1, Math.ceil(total / effectivePageSize));
    const currentPage = server ? pagination!.page : Math.min(page, pageCount);
    const pagedData = server ? sortedData : sortedData.slice((currentPage - 1) * pageSize, currentPage * pageSize);
    const showingFrom = total === 0 ? 0 : (currentPage - 1) * effectivePageSize + 1;
    const showingTo = Math.min(currentPage * effectivePageSize, total);

    const goToPage = (p: number) => (server ? pagination!.onPageChange(p) : setPage(p));
    const changePageSize = (s: number) => {
        if (server) pagination!.onPageSizeChange(s);
        else { setPageSize(s); setPage(1); }
    };
    const onSort = (key: string) => {
        if (controlled) sortState!.onSortChange(key);
        else if (sortKey === key) setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
        else { setSortKey(key); setSortDirection('asc'); }
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

    const [selectionAnchorId, setSelectionAnchorId] = useState<SelectionId | null>(null);
    const rangeShiftRef = useRef(false);

    const applyToggle = (id: SelectionId, checked: boolean) => {
        const shift = rangeShiftRef.current;
        rangeShiftRef.current = false;
        if (shift && selectionAnchorId != null && selectionAnchorId !== id) {
            const ids = pagedData.map((item) => item.id);
            const anchorIndex = ids.indexOf(selectionAnchorId);
            const targetIndex = ids.indexOf(id);
            if (anchorIndex !== -1 && targetIndex !== -1) {
                const [lo, hi] = anchorIndex < targetIndex ? [anchorIndex, targetIndex] : [targetIndex, anchorIndex];
                const next = new Set(selectedIds);
                for (let i = lo; i <= hi; i++) {
                    if (checked) next.add(ids[i]);
                    else next.delete(ids[i]);
                }
                onSelectedIdsChange(next);
                return;
            }
        }
        toggleOne(id, checked);
        setSelectionAnchorId(id);
    };

    const hasRowActions = !!(onQuickEdit || onDelete || recordRef);
    const buildRecordMenu = useCallback(
        (item: T): RecordMenuModel | null => {
            const record = recordRef ? recordRef(item) : null;
            if (!record) return null;
            return {
                record,
                onPeek: onRowClick ? () => onRowClick(item) : undefined,
                onQuickEdit: onQuickEdit ? () => onQuickEdit(item) : undefined,
                onDelete: onDelete ? () => onDelete(item) : undefined,
            };
        },
        [recordRef, onRowClick, onQuickEdit, onDelete],
    );

    const rowRefs = useRef(new Map<SelectionId, HTMLTableRowElement>());
    const [rovingId, setRovingId] = useState<SelectionId | null>(null);

    const focusRow = useCallback((id: SelectionId) => {
        setRovingId(id);
        rowRefs.current.get(id)?.focus();
    }, []);

    const handleRowKeyDown = useCallback(
        (event: KeyboardEvent<HTMLTableRowElement>, item: T, rowIndex: number) => {
            if (event.target !== event.currentTarget) return;
            if (event.key === 'ArrowDown') {
                const next = pagedData[rowIndex + 1];
                if (next) {
                    event.preventDefault();
                    focusRow(next.id);
                }
            } else if (event.key === 'ArrowUp') {
                const prev = pagedData[rowIndex - 1];
                if (prev) {
                    event.preventDefault();
                    focusRow(prev.id);
                }
            } else if (event.key === 'Home') {
                const first = pagedData[0];
                if (first) {
                    event.preventDefault();
                    focusRow(first.id);
                }
            } else if (event.key === 'End') {
                const last = pagedData[pagedData.length - 1];
                if (last) {
                    event.preventDefault();
                    focusRow(last.id);
                }
            } else if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
                const el = rowRefs.current.get(item.id);
                if (el) {
                    event.preventDefault();
                    const rect = el.getBoundingClientRect();
                    el.dispatchEvent(
                        new MouseEvent('contextmenu', {
                            bubbles: true,
                            cancelable: true,
                            clientX: rect.left + 40,
                            clientY: rect.top + rect.height / 2,
                        }),
                    );
                }
            } else if (event.key === 'Enter') {
                if (onRowClick) {
                    event.preventDefault();
                    onRowClick(item);
                } else if (detailPath) {
                    event.preventDefault();
                    router.push(detailPath(item));
                }
            }
        },
        [pagedData, focusRow, onRowClick, detailPath, router],
    );

    const rovingRowId =
        rovingId != null && pagedData.some((d) => d.id === rovingId) ? rovingId : pagedData[0]?.id ?? null;

    const [frozenOffsets, setFrozenOffsets] = useState({ avatar: 0, name: 0 });
    const frozenCleanup = useRef<(() => void) | null>(null);
    const headerRowRef = useCallback(
        (row: HTMLTableRowElement | null) => {
            frozenCleanup.current?.();
            frozenCleanup.current = null;
            if (!row) return;
            const measure = () => {
                const cells = row.children;
                const cb = (cells[0] as HTMLElement | undefined)?.offsetWidth ?? 0;
                const av = renderAvatar ? (cells[1] as HTMLElement | undefined)?.offsetWidth ?? 0 : 0;
                setFrozenOffsets({ avatar: cb, name: cb + av });
            };
            measure();
            const ro = new ResizeObserver(measure);
            Array.from(row.children).forEach((c) => ro.observe(c));
            frozenCleanup.current = () => ro.disconnect();
        },
        [renderAvatar],
    );
    const stickySeam = cn(
        "after:pointer-events-none after:absolute after:inset-y-0 after:right-0 after:hidden after:w-4 after:translate-x-full after:bg-gradient-to-r after:from-black/15 after:to-transparent after:transition-opacity after:duration-200 after:content-[''] md:after:block dark:after:from-black/45",
        edges.left ? 'after:opacity-100' : 'after:opacity-0',
    );
    const stickyHeaderBg = "bg-card before:absolute before:inset-0 before:-z-10 before:bg-muted/60 before:content-['']";

    const gridSortOptions =
        sortOptions ?? columns.flatMap((column) => isSortableColumn(column) ? [{ key: column.key, label: column.label }] : []);
    const activeSortOption = gridSortOptions.find((o) => o.key === activeSortKey);
    const gridSortBar =
        !controlled && displayMode === 'grid' && gridSortOptions.length > 0 ? (
            <div className="mb-3 flex justify-end">
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('sortBy')}
                            className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground ring-1 ring-border transition hover:text-foreground aria-expanded:text-foreground"
                        >
                            <ChevronUpDownIcon className="size-3.5" />
                            <span>{activeSortOption ? `${t('sortBy')}: ${activeSortOption.label}` : t('sortBy')}</span>
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        {gridSortOptions.map((opt) => {
                            const active = activeSortKey === opt.key;
                            return (
                                <DropdownMenuItem key={opt.key} onSelect={() => onSort(opt.key)}>
                                    <span className="flex-1">{opt.label}</span>
                                    {active &&
                                        (activeSortDirection === 'asc' ? (
                                            <ChevronUpIcon className="size-4 text-brand-dark" />
                                        ) : (
                                            <ChevronDownIcon className="size-4 text-brand-dark" />
                                        ))}
                                </DropdownMenuItem>
                            );
                        })}
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>
        ) : null;

    const selectionBar = (
        <AnimatePresence>
            {selectionActions && selectedIds.size > 0 && (
                <motion.div
                    key="selection-bar"
                    initial={reduce ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.97 }}
                    animate={reduce ? { opacity: 1 } : { opacity: 1, y: 0, scale: 1 }}
                    exit={reduce ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.97 }}
                    transition={{ duration: 0.24, ease: EASE_OUT }}
                    className="pointer-events-none fixed inset-x-0 bottom-[calc(env(safe-area-inset-bottom)+5.5rem)] z-40 flex justify-center px-4 md:bottom-6"
                >
                    <div className="pointer-events-auto flex items-center gap-1.5 rounded-full bg-card py-1.5 pr-1.5 pl-2 shadow-lg ring-1 ring-border">
                        <span className="flex items-center gap-2 pr-1 pl-1">
                            <span className="flex h-6 min-w-6 items-center justify-center rounded-full bg-brand px-1.5 text-xs font-semibold tabular-nums text-brand-foreground">
                                {selectedIds.size}
                            </span>
                            <span className="hidden text-sm text-muted-foreground sm:inline">{t('selectedLabel')}</span>
                        </span>
                        <span className="h-5 w-px shrink-0 bg-border" />
                        {selectionActions}
                        <span className="h-5 w-px shrink-0 bg-border" />
                        <button
                            type="button"
                            onClick={() => onSelectedIdsChange(new Set())}
                            aria-label={t('clearSelection')}
                            className="flex size-8 shrink-0 items-center justify-center rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground active:scale-95"
                        >
                            <XMarkIcon className="size-4" strokeWidth={2} />
                        </button>
                    </div>
                </motion.div>
            )}
        </AnimatePresence>
    );

    const pager = (
        <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-3 text-sm text-muted-foreground">
                <span className="tabular-nums">{t('showing', { from: showingFrom, to: showingTo, total })}</span>
                <span className="hidden h-4 w-px bg-border sm:block" />
                <label className="hidden items-center gap-2 sm:flex">
                    <span>{t('rowsPerPage')}</span>
                    <Select value={String(effectivePageSize)} onValueChange={(v) => changePageSize(Number(v))}>
                        <SelectTrigger size="sm" className="rounded-full"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            {PAGE_SIZES.map((n) => <SelectItem key={n} value={String(n)}>{n}</SelectItem>)}
                        </SelectContent>
                    </Select>
                </label>
            </div>
            {pageCount > 1 && (
                <Pagination className="mx-0 w-auto justify-end">
                    <PaginationContent>
                        <PaginationItem>
                            <PaginationPrevious
                                disabled={currentPage === 1}
                                onClick={() => goToPage(currentPage - 1)}
                                aria-label={t('previousPage')}
                            />
                        </PaginationItem>
                        {pageList(currentPage, pageCount).map((it, i) =>
                            it === 'gap' ? (
                                <PaginationItem key={`gap-${i}`}>
                                    <PaginationEllipsis />
                                </PaginationItem>
                            ) : (
                                <PaginationItem key={it}>
                                    <PaginationLink isActive={it === currentPage} onClick={() => goToPage(it)}>
                                        {it}
                                    </PaginationLink>
                                </PaginationItem>
                            ),
                        )}
                        <PaginationItem>
                            <PaginationNext
                                disabled={currentPage === pageCount}
                                onClick={() => goToPage(currentPage + 1)}
                                aria-label={t('nextPage')}
                            />
                        </PaginationItem>
                    </PaginationContent>
                </Pagination>
            )}
        </div>
    );

    if (loading && pagedData.length === 0) {
        if (displayMode === 'grid') {
            return (
                <div className={gridClassName} aria-busy>
                    {Array.from({ length: 8 }).map((_, i) => (
                        <div key={i} className="overflow-hidden rounded-2xl border border-border">
                            <div className="aspect-[4/3] animate-pulse bg-muted" />
                            <div className="space-y-2 p-3">
                                <div className="h-3.5 w-3/4 animate-pulse rounded bg-muted" />
                                <div className="h-3 w-1/2 animate-pulse rounded bg-muted" />
                            </div>
                        </div>
                    ))}
                </div>
            );
        }
        return (
            <div className="space-y-2 rounded-2xl border border-border bg-card p-4">
                {Array.from({ length: 6 }).map((_, i) => (
                    <div key={i} className="h-9 animate-pulse rounded-lg bg-muted" />
                ))}
            </div>
        );
    }

    if (pagedData.length === 0) {
        if (!filtersActive && emptyState !== undefined) {
            return (
                <>
                    {emptyState}
                    {selectionBar}
                </>
            );
        }
        return (
            <>
                <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center">
                    <div className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
                        <InboxIcon className="size-6" />
                    </div>
                    <p className="mx-auto mt-4 max-w-sm text-sm font-medium text-muted-foreground">
                        {filtersActive ? t('noResults') : t('emptyState')}
                    </p>
                    {filtersActive && onClearFilters && (
                        <Button variant="outline" className="mt-5" onClick={onClearFilters}>
                            {t('clearFilters')}
                        </Button>
                    )}
                </div>
                {selectionBar}
            </>
        );
    }

    if (displayMode === 'grid') {
        return (
            <>
                {gridSortBar}
                <div className={cn(gridClassName, loading && 'opacity-60 transition-opacity')} aria-busy={loading}>
                    <AnimatePresence initial={false}>
                        {pagedData.map((item) => {
                            const menuModel = buildRecordMenu(item);
                            const card = renderCard(item, { onQuickEdit, onDelete });
                            return (
                                <motion.div
                                    key={item.id}
                                    initial={false}
                                    exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.96, transition: { duration: 0.18, ease: EASE_OUT } }}
                                    className={cn(
                                        'rounded-2xl',
                                        selectedIds.has(item.id) && 'outline-2 outline-offset-2 outline-brand',
                                    )}
                                >
                                    {menuModel ? (
                                        <RecordContextMenu model={menuModel}>
                                            <div className="contents">{card}</div>
                                        </RecordContextMenu>
                                    ) : (
                                        card
                                    )}
                                </motion.div>
                            );
                        })}
                    </AnimatePresence>
                </div>
                {pager}
                {selectionBar}
            </>
        );
    }

    return (
        <>
            <div className={cn('relative overflow-hidden rounded-2xl border border-border bg-card', loading && 'opacity-60 transition-opacity')} aria-busy={loading}>
                <div ref={scrollRef} className="overflow-x-auto data-[dragging=true]:cursor-grabbing data-[dragging=true]:select-none data-[dragging=true]:[&_thead]:cursor-grabbing">
                    <table data-density={density} className="w-full min-w-max border-collapse text-left text-sm">
                        <thead>
                            <tr ref={headerRowRef} className="cursor-grab border-b border-border bg-muted/60">
                                <th className={cn('w-12 px-4 py-2.5 md:sticky md:left-0 md:z-20', stickyHeaderBg)}>
                                    <Checkbox
                                        checked={someSelected ? 'indeterminate' : allSelected}
                                        onCheckedChange={(checked) => toggleAll(checked === true)}
                                        aria-label={t('selectAllAria', { entityLabel })}
                                        className={CHECKBOX_CLASS}
                                    />
                                </th>
                                {renderAvatar && <th className={cn('w-12 px-4 py-2.5 md:sticky md:z-20', stickyHeaderBg)} style={{ left: frozenOffsets.avatar }} aria-hidden />}
                                {columns.map((col, colIndex) => {
                                    const active = activeSortKey === col.key;
                                    const sortable = isSortableColumn(col);
                                    const Icon = active
                                        ? activeSortDirection === 'asc'
                                            ? ChevronUpIcon
                                            : ChevronDownIcon
                                        : ChevronUpDownIcon;
                                    return (
                                        <th
                                            key={col.key}
                                            aria-sort={
                                                sortable
                                                    ? active
                                                        ? activeSortDirection === 'asc'
                                                            ? 'ascending'
                                                            : 'descending'
                                                        : 'none'
                                                    : undefined
                                            }
                                            className={cn(
                                                'px-4 py-2.5 text-xs font-semibold tracking-wide whitespace-nowrap text-muted-foreground uppercase',
                                                col.widthClass,
                                                colIndex === 0 && cn('md:sticky md:z-20', stickyHeaderBg, stickySeam),
                                            )}
                                            style={colIndex === 0 ? { left: frozenOffsets.name } : undefined}
                                        >
                                            {col.renderHeader ? (
                                                col.renderHeader()
                                            ) : sortable ? (
                                                <button
                                                    type="button"
                                                    onClick={() => onSort(col.key)}
                                                    className={cn(
                                                        'inline-flex items-center gap-1 transition-colors hover:text-foreground',
                                                        active && 'text-foreground',
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
                                {addColumnSlot && <th className="w-10 px-2 py-2.5">{addColumnSlot}</th>}
                                {hasRowActions && <th className="w-12 px-4 py-2.5" aria-hidden />}
                            </tr>
                        </thead>
                        <tbody>
                            {pagedData.map((item, rowIndex) => {
                                const isSelected = selectedIds.has(item.id);
                                const isActive = activeId != null && item.id === activeId;
                                const clickable = !!(onRowClick || detailPath);
                                const menuModel = buildRecordMenu(item);
                                const stickyBodyBg = isSelected
                                    ? "bg-card before:absolute before:inset-0 before:-z-10 before:bg-brand-light/40 before:transition-colors before:content-[''] group-hover:before:bg-brand-light/55"
                                    : 'bg-card transition-colors group-hover:bg-muted';
                                const row = (
                                    <tr
                                        key={item.id}
                                        ref={(el) => {
                                            if (el) rowRefs.current.set(item.id, el);
                                            else rowRefs.current.delete(item.id);
                                        }}
                                        data-state={isSelected ? 'selected' : undefined}
                                        tabIndex={menuModel ? (item.id === rovingRowId ? 0 : -1) : undefined}
                                        aria-keyshortcuts={menuModel ? 'Shift+F10' : undefined}
                                        onKeyDown={menuModel ? (event) => handleRowKeyDown(event, item, rowIndex) : undefined}
                                        onFocus={menuModel ? () => setRovingId((prev) => (prev === item.id ? prev : item.id)) : undefined}
                                        className={cn(
                                            'group border-b border-border outline-hidden transition-colors last:border-b-0 focus-visible:outline-2 focus-visible:outline-solid focus-visible:-outline-offset-2 focus-visible:outline-brand',
                                            menuModel && '[&:focus-visible>td]:bg-brand-light/40',
                                            clickable && 'cursor-pointer',
                                            isSelected ? 'bg-brand-light/40 hover:bg-brand-light/55' : 'hover:bg-muted',
                                            isActive && 'ring-2 ring-inset ring-brand',
                                        )}
                                        onClick={() => {
                                            if (onRowClick) onRowClick(item);
                                            else if (detailPath) router.push(detailPath(item));
                                        }}
                                    >
                                        <td className={cn('px-4 py-2.5 md:sticky md:left-0 md:z-10', stickyBodyBg)} onClick={(e) => e.stopPropagation()}>
                                            <Checkbox
                                                checked={isSelected}
                                                onClick={(event) => {
                                                    rangeShiftRef.current = event.shiftKey;
                                                }}
                                                onCheckedChange={(checked) => applyToggle(item.id, checked === true)}
                                                aria-label={t('selectItemAria', { name: item.name ?? entityLabel })}
                                                className={CHECKBOX_CLASS}
                                            />
                                        </td>
                                        {renderAvatar && <td className={cn('px-4 py-2.5 md:sticky md:z-10', stickyBodyBg)} style={{ left: frozenOffsets.avatar }}>{renderAvatar(item)}</td>}
                                        {columns.map((col, colIndex) => {
                                            const content = col.render
                                                ? col.render(item)
                                                : (item as unknown as Record<string, ReactNode>)[col.key];
                                            if (col.copyable) {
                                                const { label, getValue } = col.copyable;
                                                return (
                                                    <td
                                                        key={col.key}
                                                        className={cn(
                                                            'px-4 py-2.5 whitespace-nowrap text-foreground transition-colors hover:text-brand-dark',
                                                            colIndex === 0 && cn('md:sticky md:z-10', stickyBodyBg, stickySeam),
                                                        )}
                                                        style={colIndex === 0 ? { left: frozenOffsets.name } : undefined}
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
                                                <td
                                                    key={col.key}
                                                    className={cn(
                                                        'px-4 py-2.5 whitespace-nowrap text-foreground',
                                                        colIndex === 0 && cn('md:sticky md:z-10', stickyBodyBg, stickySeam),
                                                    )}
                                                    style={colIndex === 0 ? { left: frozenOffsets.name } : undefined}
                                                >
                                                    {content}
                                                </td>
                                            );
                                        })}
                                        {addColumnSlot && <td className="px-2 py-2.5" aria-hidden />}
                                        {hasRowActions && (
                                            <td className="px-2 py-2.5 text-right" onClick={(e) => e.stopPropagation()}>
                                                {menuModel ? (
                                                    <RecordActionMenuTrigger model={menuModel} />
                                                ) : (
                                                    <RowActions
                                                        name={item.name ?? entityLabel}
                                                        onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                                        onDelete={onDelete ? () => onDelete(item) : undefined}
                                                        actionsAria={t('rowActionsAria', { name: item.name ?? entityLabel })}
                                                        quickEditLabel={t('quickEdit')}
                                                        deleteLabel={t('delete')}
                                                    />
                                                )}
                                            </td>
                                        )}
                                    </tr>
                                );
                                return menuModel ? (
                                    <RecordContextMenu key={item.id} model={menuModel}>
                                        {row}
                                    </RecordContextMenu>
                                ) : (
                                    row
                                );
                            })}
                        </tbody>
                    </table>
                </div>
                <div
                    aria-hidden
                    className={cn(
                        'pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-card to-transparent transition-opacity duration-200',
                        edges.right ? 'opacity-100' : 'opacity-0',
                    )}
                />
            </div>
            {pager}
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
                <RecordActionsTriggerButton ariaLabel={actionsAria} />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40">
                {onQuickEdit && (
                    <DropdownMenuItem onSelect={() => onQuickEdit()}>
                        <PencilSquareIcon className="size-4 text-muted-foreground" />
                        {quickEditLabel}
                    </DropdownMenuItem>
                )}
                {onQuickEdit && onDelete && <DropdownMenuSeparator />}
                {onDelete && (
                    <DropdownMenuItem
                        className="text-destructive hover:bg-destructive/10"
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
