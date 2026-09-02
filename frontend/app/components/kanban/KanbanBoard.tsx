'use client';

import { type ReactNode, useMemo, useRef, useState } from 'react';
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
    renderCard: (item: T) => ReactNode;
    /** Persist a move; the board reverts its optimistic state if this rejects. */
    onMove?: (itemId: number, toColumnId: string, index: number) => Promise<void>;
    reduce: boolean;
    emptyHint?: string;
    countLabel: (count: number) => string;
    accessibility?: { announcements?: Announcements; screenReaderInstructions?: ScreenReaderInstructions };
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

const boardCollisionDetection: CollisionDetection = (args) => {
    const pointerCollisions = pointerWithin(args);
    return pointerCollisions.length > 0 ? pointerCollisions : closestCorners(args);
};

export default function KanbanBoard<T>(props: KanbanBoardProps<T>) {
    const { columns, items, getId, getColumnId, getPosition, renderCard, onMove, reduce, emptyHint, countLabel, accessibility } = props;

    const incoming = useMemo(
        () => groupItems(columns, items, getId, getColumnId, getPosition),
        [columns, items, getId, getColumnId, getPosition],
    );
    const incomingKey = useMemo(() => JSON.stringify(incoming), [incoming]);

    const [columnItems, setColumnItems] = useState<ColumnItems>(incoming);
    const snapshotRef = useRef<ColumnItems>(incoming);
    const snapshotColumnByItemIdRef = useRef<Map<number, string>>(new Map());
    const [activeId, setActiveId] = useState<number | null>(null);

    const [syncedKey, setSyncedKey] = useState(incomingKey);
    if (syncedKey !== incomingKey && activeId == null) {
        setSyncedKey(incomingKey);
        setColumnItems(incoming);
    }

    const itemsById = useMemo(
        () => new Map<number, T>(items.map((item) => [getId(item), item])),
        [items, getId],
    );
    const columnByItemId = useMemo(() => indexColumns(columnItems), [columnItems]);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    const commit = (next: ColumnItems) => setColumnItems(next);

    const onDragStart = (event: DragStartEvent) => {
        if (!onMove) return;
        snapshotRef.current = columnItems;
        snapshotColumnByItemIdRef.current = columnByItemId;
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
            return;
        }
        const overStr = String(over.id);
        const current = columnItems;
        const toCol = overStr.startsWith(COL_PREFIX) ? overStr.slice(COL_PREFIX.length) : columnByItemId.get(Number(over.id));
        if (!toCol || !current[toCol]) {
            commit(snapshot);
            return;
        }

        let toIds = current[toCol];
        const oldIndex = toIds.indexOf(activeId);
        if (oldIndex < 0) {
            const fromCol = columnByItemId.get(activeId);
            const insertAt = overStr.startsWith(COL_PREFIX)
                ? toIds.length
                : Math.max(0, toIds.indexOf(Number(over.id)));
            toIds = [...toIds.slice(0, insertAt), activeId, ...toIds.slice(insertAt)];
            const next = { ...current, [toCol]: toIds };
            if (fromCol && fromCol !== toCol) {
                next[fromCol] = current[fromCol].filter((id) => id !== activeId);
            }
            commit(next);
        } else {
            let newIndex = overStr.startsWith(COL_PREFIX) ? toIds.length - 1 : toIds.indexOf(Number(over.id));
            if (newIndex < 0) newIndex = toIds.length - 1;
            if (oldIndex !== newIndex) {
                toIds = arrayMove(toIds, oldIndex, newIndex);
                commit({ ...current, [toCol]: toIds });
            }
        }
        const finalIndex = toIds.indexOf(activeId);

        const originCol = snapshotColumnByItemIdRef.current.get(activeId);
        const originIndex = originCol ? snapshot[originCol].indexOf(activeId) : -1;
        if (originCol === toCol && originIndex === finalIndex) return;

        void onMove(activeId, toCol, finalIndex).catch(() => commit(snapshot));
    };

    const activeItem = activeId != null ? itemsById.get(activeId) : undefined;

    const { ref: scrollRef, edges } = useDragScroll<HTMLDivElement>({
        leftDragSelector: '[data-kanban-board]',
        excludeDragSelector: '[data-kanban-card]',
    });

    return (
        <DndContext
            sensors={sensors}
            collisionDetection={boardCollisionDetection}
            onDragStart={onDragStart}
            onDragOver={onDragOver}
            onDragEnd={onDragEnd}
            onDragCancel={() => { setActiveId(null); commit(snapshotRef.current); }}
            accessibility={accessibility}
        >
            <div className="relative">
                <div
                    ref={scrollRef}
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
                            reduce={reduce}
                            draggable={onMove !== undefined}
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
                    <div className={reduce ? 'cursor-grabbing' : 'cursor-grabbing scale-[1.02] shadow-2xl'}>
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
    reduce,
    draggable,
    emptyHint,
    countLabel,
}: {
    col: KanbanColumnDef;
    ids: number[];
    itemsById: Map<number, T>;
    renderCard: (item: T) => ReactNode;
    reduce: boolean;
    draggable: boolean;
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
                <SortableContext items={ids} strategy={verticalListSortingStrategy}>
                    <ul className="flex min-h-24 flex-col gap-2 p-2">
                        {ids.map((id) => {
                            const item = itemsById.get(id);
                            if (!item) return null;
                            return (
                                <SortableCard key={id} id={id} reduce={reduce} draggable={draggable}>
                                    {renderCard(item)}
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

function SortableCard({ id, reduce, draggable, children }: { id: number; reduce: boolean; draggable: boolean; children: ReactNode }) {
    const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id, disabled: !draggable });
    const style = {
        transform: CSS.Transform.toString(transform),
        transition: reduce ? undefined : transition,
    };
    return (
        <li
            ref={setNodeRef}
            style={style}
            data-kanban-card
            {...(draggable ? attributes : {})}
            {...(draggable ? listeners : {})}
            className={`list-none outline-none ${draggable ? 'touch-none' : ''} ${isDragging ? 'opacity-40' : ''}`}
        >
            {children}
        </li>
    );
}
