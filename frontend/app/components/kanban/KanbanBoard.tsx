'use client';

import { type ReactNode, useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
    DndContext,
    DragOverlay,
    KeyboardSensor,
    PointerSensor,
    closestCorners,
    pointerWithin,
    useDroppable,
    useSensor,
    useSensors,
    type Announcements,
    type CollisionDetection,
    type DragEndEvent,
    type DragOverEvent,
    type DragStartEvent,
    type ScreenReaderInstructions,
} from '@dnd-kit/core';
import {
    SortableContext,
    arrayMove,
    sortableKeyboardCoordinates,
    useSortable,
    verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Bars3Icon } from '@heroicons/react/24/outline';
import { IconButton } from '@/components/ui/icon-button';

import { useDragScroll } from '@/app/hooks/useDragScroll';

const COL_PREFIX = 'col:';

export interface KanbanColumnDef {
    id: string;
    label: string;
    /** CSS color (design token) for the column's accent dot. */
    accent?: string;
    /** Optional node rendered at the foot of the column (e.g. an add button). */
    footer?: ReactNode;
}

export interface KanbanBoardProps<T> {
    columns: KanbanColumnDef[];
    items: T[];
    getId: (item: T) => number;
    getColumnId: (item: T) => string;
    getPosition: (item: T) => number;
    renderCard: (item: T, dragHandle?: ReactNode) => ReactNode;
    dragLabel: (item: T) => string;
    /** Seek consent with the source placement visible; false cancels before persistence. */
    onBeforeMove?: (itemId: number, toColumnId: string, index: number) => Promise<boolean>;
    /** Persist a move and optionally return its saved placement within the visible board. */
    onMove?: (itemId: number, toColumnId: string, index: number) => Promise<void | boolean | KanbanMoveResult>;
    reduce: boolean;
    emptyHint?: string;
    countLabel: (count: number) => string;
    accessibility?: {
        announcements?: Announcements;
        screenReaderInstructions?: ScreenReaderInstructions;
        moveRestored?: (itemId: number, columnId: string, position: number) => string;
    };
}

/** The server-confirmed visible placement of a persisted move. */
export interface KanbanMoveResult {
    columnId: string;
    /** Visible index after saving, or -1 when the saved item no longer matches the board. */
    index: number;
}

type ColumnItems = Record<string, number[]>;

function groupItems<T>(
    columns: KanbanColumnDef[],
    items: T[],
    getId: (item: T) => number,
    getColumnId: (item: T) => string,
    getPosition: (item: T) => number,
): ColumnItems {
    const map: ColumnItems = {};
    for (const col of columns) map[col.id] = [];
    const sorted = [...items].sort((a, b) => getPosition(a) - getPosition(b) || getId(a) - getId(b));
    for (const item of sorted) {
        const colId = getColumnId(item);
        if (map[colId]) map[colId].push(getId(item));
    }
    return map;
}

function indexColumns(map: ColumnItems): Map<number, string> {
    const index = new Map<number, string>();
    for (const [col, ids] of Object.entries(map)) {
        for (const id of ids) index.set(id, col);
    }
    return index;
}

function placeItem(current: ColumnItems, itemId: number, saved: KanbanMoveResult): ColumnItems {
    const next = Object.fromEntries(Object.entries(current).map(([column, ids]) => [column, ids.filter((id) => id !== itemId)]));
    if (saved.index >= 0 && next[saved.columnId]) next[saved.columnId].splice(Math.min(saved.index, next[saved.columnId].length), 0, itemId);
    return next;
}

const boardCollisionDetection: CollisionDetection = (args) => {
    const pointerCollisions = pointerWithin(args);
    return pointerCollisions.length > 0 ? pointerCollisions : closestCorners(args);
};

export default function KanbanBoard<T>(props: KanbanBoardProps<T>) {
    const { columns, items, getId, getColumnId, getPosition, renderCard, dragLabel, onBeforeMove, onMove, reduce, emptyHint, countLabel, accessibility } = props;

    const incoming = useMemo(
        () => groupItems(columns, items, getId, getColumnId, getPosition),
        [columns, items, getId, getColumnId, getPosition],
    );
    const incomingKey = useMemo(() => JSON.stringify(incoming), [incoming]);

    const [columnItems, setColumnItems] = useState<ColumnItems>(incoming);
    const snapshotRef = useRef<ColumnItems>(incoming);
    const snapshotColumnByItemIdRef = useRef<Map<number, string>>(new Map());
    const snapshotIncomingKeyRef = useRef(incomingKey);
    const [activeId, setActiveId] = useState<number | null>(null);
    const [saving, setSaving] = useState(false);
    const boardRef = useRef<HTMLDivElement>(null);
    const [moveAnnouncement, setMoveAnnouncement] = useState('');

    const [syncedKey, setSyncedKey] = useState(incomingKey);
    if (syncedKey !== incomingKey && activeId == null && !saving) {
        setSyncedKey(incomingKey);
        setColumnItems(incoming);
    }

    const itemsById = useMemo(
        () => new Map<number, T>(items.map((item) => [getId(item), item])),
        [items, getId],
    );
    const columnByItemId = useMemo(() => indexColumns(columnItems), [columnItems]);
    const latestIncomingRef = useRef({ columns: incoming, key: incomingKey, itemsById });
    useLayoutEffect(() => {
        latestIncomingRef.current = { columns: incoming, key: incomingKey, itemsById };
    }, [incoming, incomingKey, itemsById]);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    const commit = (next: ColumnItems) => setColumnItems(next);
    const restoreFocus = (event: DragEndEvent) => {
        requestAnimationFrame(() => boardRef.current?.querySelector<HTMLButtonElement>(`[data-kanban-drag-handle="${Number(event.active.id)}"]`)?.focus());
    };

    const onDragStart = (event: DragStartEvent) => {
        if (!onMove) return;
        setMoveAnnouncement('');
        snapshotRef.current = columnItems;
        snapshotColumnByItemIdRef.current = columnByItemId;
        snapshotIncomingKeyRef.current = incomingKey;
        setActiveId(Number(event.active.id));
    };

    const onDragOver = (event: DragOverEvent) => {
        if (!onMove) return;
        const { active, over } = event;
        if (!over) return;
        const activeId = Number(active.id);
        const overStr = String(over.id);
        const current = columnItems;
        const fromCol = columnByItemId.get(activeId);
        const toCol = overStr.startsWith(COL_PREFIX) ? overStr.slice(COL_PREFIX.length) : columnByItemId.get(Number(over.id));
        if (!fromCol || !toCol || !current[toCol] || fromCol === toCol) return;

        const fromIds = current[fromCol].filter((id) => id !== activeId);
        const toIds = [...current[toCol]];
        let insertAt = toIds.length;
        if (!overStr.startsWith(COL_PREFIX)) {
            const overIndex = toIds.indexOf(Number(over.id));
            if (overIndex >= 0) insertAt = overIndex;
        }
        toIds.splice(insertAt, 0, activeId);
        commit({ ...current, [fromCol]: fromIds, [toCol]: toIds });
    };

    const onDragEnd = (event: DragEndEvent) => {
        if (!onMove) return;
        const { active, over } = event;
        setActiveId(null);
        const activeId = Number(active.id);
        const snapshot = snapshotRef.current;
        if (!over) {
            commit(snapshot);
            restoreFocus(event);
            return;
        }
        const overStr = String(over.id);
        const current = columnItems;
        const toCol = overStr.startsWith(COL_PREFIX) ? overStr.slice(COL_PREFIX.length) : columnByItemId.get(Number(over.id));
        if (!toCol || !current[toCol]) {
            commit(snapshot);
            restoreFocus(event);
            return;
        }

        let next = current;
        let toIds = current[toCol];
        const oldIndex = toIds.indexOf(activeId);
        if (oldIndex < 0) {
            const fromCol = columnByItemId.get(activeId);
            const insertAt = overStr.startsWith(COL_PREFIX)
                ? toIds.length
                : Math.max(0, toIds.indexOf(Number(over.id)));
            toIds = [...toIds.slice(0, insertAt), activeId, ...toIds.slice(insertAt)];
            next = { ...current, [toCol]: toIds };
            if (fromCol && fromCol !== toCol) {
                next[fromCol] = current[fromCol].filter((id) => id !== activeId);
            }
        } else {
            let newIndex = overStr.startsWith(COL_PREFIX) ? toIds.length - 1 : toIds.indexOf(Number(over.id));
            if (newIndex < 0) newIndex = toIds.length - 1;
            if (oldIndex !== newIndex) {
                toIds = arrayMove(toIds, oldIndex, newIndex);
                next = { ...current, [toCol]: toIds };
            }
        }
        const finalIndex = toIds.indexOf(activeId);

        const originCol = snapshotColumnByItemIdRef.current.get(activeId);
        const originIndex = originCol ? snapshot[originCol].indexOf(activeId) : -1;
        const restorePlacement = () => {
            const latest = latestIncomingRef.current;
            const restored = latest.key === snapshotIncomingKeyRef.current ? snapshot : latest.columns;
            commit(restored);
            setSyncedKey(latest.key);
            const column = indexColumns(restored).get(activeId);
            const item = latest.itemsById.get(activeId);
            if (column) setMoveAnnouncement(accessibility?.moveRestored?.(activeId, column, item ? getPosition(item) : restored[column].indexOf(activeId)) ?? '');
        };
        if (originCol === toCol && originIndex === finalIndex) {
            restorePlacement();
            restoreFocus(event);
            return;
        }

        setSaving(true);
        if (onBeforeMove) commit(snapshot);
        else commit(next);
        const persist = async () => {
            if (onBeforeMove) {
                if (!await onBeforeMove(activeId, toCol, finalIndex)) {
                    restorePlacement();
                    return;
                }
                commit(next);
            }
            const saved = await onMove(activeId, toCol, finalIndex);
            if (saved === false) restorePlacement();
            else {
                const latest = latestIncomingRef.current;
                const placement = saved && typeof saved === 'object' ? saved : { columnId: toCol, index: finalIndex };
                setSyncedKey(latest.key);
                setColumnItems((current) => placeItem(latest.key === snapshotIncomingKeyRef.current ? current : latest.columns, activeId, placement));
            }
        };
        void persist()
            .catch(restorePlacement)
            .finally(() => { setSaving(false); restoreFocus(event); });
    };

    const activeItem = activeId != null ? itemsById.get(activeId) : undefined;

    const { ref: scrollRef, edges } = useDragScroll<HTMLDivElement>({
        leftDragSelector: '[data-kanban-board]',
        excludeDragSelector: '[data-kanban-card]',
    });
    const setBoardRef = useCallback((node: HTMLDivElement | null) => {
        boardRef.current = node;
        scrollRef(node);
    }, [scrollRef]);

    return (
        <DndContext
            sensors={sensors}
            collisionDetection={boardCollisionDetection}
            onDragStart={onDragStart}
            onDragOver={onDragOver}
            onDragEnd={onDragEnd}
            onDragCancel={(event) => { setActiveId(null); commit(snapshotRef.current); restoreFocus(event); }}
            accessibility={accessibility}
        >
            <div role="status" aria-live="polite" aria-atomic="true" className="sr-only">{moveAnnouncement}</div>
            <div className="relative">
                <div
                    ref={setBoardRef}
                    data-kanban-board
                    className="flex gap-4 overflow-x-auto pb-2 data-[dragging=true]:cursor-grabbing data-[dragging=true]:select-none"
                >
                    {columns.map((col) => (
                        <KanbanColumn
                            key={col.id}
                            col={col}
                            ids={columnItems[col.id] ?? []}
                            itemsById={itemsById}
                            renderCard={renderCard}
                            dragLabel={dragLabel}
                            reduce={reduce}
                            draggable={onMove !== undefined}
                            disabled={saving}
                            emptyHint={emptyHint}
                            countLabel={countLabel}
                        />
                    ))}
                </div>
                <div
                    aria-hidden
                    className={`pointer-events-none absolute inset-y-0 left-0 w-10 bg-gradient-to-r from-background to-transparent transition-opacity duration-200 ${edges.left ? 'opacity-100' : 'opacity-0'}`}
                />
                <div
                    aria-hidden
                    className={`pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-background to-transparent transition-opacity duration-200 ${edges.right ? 'opacity-100' : 'opacity-0'}`}
                />
            </div>
            <DragOverlay>
                {activeItem ? (
                    <div aria-hidden inert className={reduce ? 'cursor-grabbing' : 'cursor-grabbing scale-[1.02] shadow-2xl'}>
                        {renderCard(activeItem)}
                    </div>
                ) : null}
            </DragOverlay>
        </DndContext>
    );
}

function KanbanColumn<T>({
    col,
    ids,
    itemsById,
    renderCard,
    dragLabel,
    reduce,
    draggable,
    disabled,
    emptyHint,
    countLabel,
}: {
    col: KanbanColumnDef;
    ids: number[];
    itemsById: Map<number, T>;
    renderCard: (item: T, dragHandle?: ReactNode) => ReactNode;
    dragLabel: (item: T) => string;
    reduce: boolean;
    draggable: boolean;
    disabled: boolean;
    emptyHint?: string;
    countLabel: (count: number) => string;
}) {
    const { setNodeRef, isOver } = useDroppable({ id: COL_PREFIX + col.id });
    return (
        <section className="flex w-80 shrink-0 flex-col rounded-2xl bg-muted/40 ring-1 ring-border">
            <header className="flex items-center gap-2 px-4 pt-3 pb-1">
                {col.accent && <span className="size-2 rounded-full" style={{ backgroundColor: col.accent }} aria-hidden />}
                <h3 className="text-sm font-semibold text-foreground">{col.label}</h3>
                <span className="ml-auto text-xs tabular-nums text-muted-foreground">{countLabel(ids.length)}</span>
            </header>
            <div
                ref={setNodeRef}
                className={`flex-1 rounded-b-2xl transition-colors duration-150 ${isOver ? 'bg-brand/5 ring-2 ring-inset ring-brand/40' : ''}`}
            >
                <SortableContext id={COL_PREFIX + col.id} items={ids} strategy={verticalListSortingStrategy}>
                    <ul className="flex min-h-24 flex-col gap-2 p-2">
                        {ids.map((id) => {
                            const item = itemsById.get(id);
                            if (!item) return null;
                            return (
                                <SortableCard key={id} id={id} reduce={reduce} draggable={draggable} disabled={disabled} label={dragLabel(item)}>
                                    {(handle) => renderCard(item, handle)}
                                </SortableCard>
                            );
                        })}
                        {ids.length === 0 && emptyHint && (
                            <li className="rounded-xl border border-dashed border-border px-3 py-6 text-center text-xs text-muted-foreground">
                                {emptyHint}
                            </li>
                        )}
                    </ul>
                </SortableContext>
                {col.footer}
            </div>
        </section>
    );
}

function SortableCard({ id, reduce, draggable, disabled, label, children }: {
    id: number; reduce: boolean; draggable: boolean; disabled: boolean; label: string; children: (handle?: ReactNode) => ReactNode;
}) {
    const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({ id, disabled: !draggable || disabled });
    const style = {
        transform: CSS.Transform.toString(transform),
        transition: reduce ? undefined : transition,
    };
    return (
        <li
            ref={setNodeRef}
            style={style}
            data-kanban-card
            className={`list-none rounded-xl focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-ring forced-colors:focus-within:outline-[Highlight] ${isDragging ? 'opacity-40' : ''}`}
        >
            {children(draggable ? (
                <IconButton
                    ref={setActivatorNodeRef}
                    {...attributes}
                    {...listeners}
                    data-kanban-drag-handle={id}
                    label={label}
                    variant="ghost"
                    size="icon-toolbar"
                    disabled={disabled}
                    className="shrink-0 touch-none cursor-grab active:cursor-grabbing"
                >
                    <Bars3Icon className="size-4" />
                </IconButton>
            ) : undefined)}
        </li>
    );
}
