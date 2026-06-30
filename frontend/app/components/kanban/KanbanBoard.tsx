'use client';

import { type ReactNode, useRef, useState } from 'react';
import {
    DndContext,
    DragOverlay,
    KeyboardSensor,
    PointerSensor,
    closestCorners,
    useDroppable,
    useSensor,
    useSensors,
    type Announcements,
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
    onMove: (itemId: number, toColumnId: string, index: number) => Promise<void>;
    reduce: boolean;
    emptyHint?: string;
    countLabel: (count: number) => string;
    accessibility?: { announcements?: Announcements; screenReaderInstructions?: ScreenReaderInstructions };
}

type ColumnItems = Record<string, number[]>;

function groupItems<T>(props: KanbanBoardProps<T>): ColumnItems {
    const { columns, items, getId, getColumnId, getPosition } = props;
    const map: ColumnItems = {};
    for (const col of columns) map[col.id] = [];
    const sorted = [...items].sort((a, b) => getPosition(a) - getPosition(b) || getId(a) - getId(b));
    for (const item of sorted) {
        const colId = getColumnId(item);
        if (map[colId]) map[colId].push(getId(item));
    }
    return map;
}

function findColumn(map: ColumnItems, id: number): string | null {
    for (const [col, ids] of Object.entries(map)) {
        if (ids.includes(id)) return col;
    }
    return null;
}

export default function KanbanBoard<T>(props: KanbanBoardProps<T>) {
    const { columns, items, getId, renderCard, onMove, reduce, emptyHint, countLabel, accessibility } = props;

    const incoming = groupItems(props);
    const incomingKey = JSON.stringify(incoming);

    const [columnItems, setColumnItems] = useState<ColumnItems>(incoming);
    const snapshotRef = useRef<ColumnItems>(incoming);
    const [activeId, setActiveId] = useState<number | null>(null);

    const [syncedKey, setSyncedKey] = useState(incomingKey);
    if (syncedKey !== incomingKey && activeId == null) {
        setSyncedKey(incomingKey);
        setColumnItems(incoming);
    }

    const itemsById = new Map<number, T>(items.map((item) => [getId(item), item]));

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    const commit = (next: ColumnItems) => setColumnItems(next);

    const onDragStart = (event: DragStartEvent) => {
        snapshotRef.current = columnItems;
        setActiveId(Number(event.active.id));
    };

    const onDragOver = (event: DragOverEvent) => {
        const { active, over } = event;
        if (!over) return;
        const activeId = Number(active.id);
        const overStr = String(over.id);
        const current = columnItems;
        const fromCol = findColumn(current, activeId);
        const toCol = overStr.startsWith(COL_PREFIX) ? overStr.slice(COL_PREFIX.length) : findColumn(current, Number(over.id));
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
        const toCol = overStr.startsWith(COL_PREFIX) ? overStr.slice(COL_PREFIX.length) : findColumn(current, Number(over.id));
        if (!toCol || !current[toCol]) {
            commit(snapshot);
            return;
        }

        let toIds = current[toCol];
        const oldIndex = toIds.indexOf(activeId);
        let newIndex = overStr.startsWith(COL_PREFIX) ? toIds.length - 1 : toIds.indexOf(Number(over.id));
        if (newIndex < 0) newIndex = toIds.length - 1;
        if (oldIndex >= 0 && oldIndex !== newIndex) {
            toIds = arrayMove(toIds, oldIndex, newIndex);
            commit({ ...current, [toCol]: toIds });
        }
        const finalIndex = toIds.indexOf(activeId);

        const originCol = findColumn(snapshot, activeId);
        const originIndex = originCol ? snapshot[originCol].indexOf(activeId) : -1;
        if (originCol === toCol && originIndex === finalIndex) return;

        void onMove(activeId, toCol, finalIndex).catch(() => commit(snapshot));
    };

    const activeItem = activeId != null ? itemsById.get(activeId) : undefined;

    return (
        <DndContext
            sensors={sensors}
            collisionDetection={closestCorners}
            onDragStart={onDragStart}
            onDragOver={onDragOver}
            onDragEnd={onDragEnd}
            onDragCancel={() => { setActiveId(null); commit(snapshotRef.current); }}
            accessibility={accessibility}
        >
            <div className="flex gap-4 overflow-x-auto pb-2">
                {columns.map((col) => (
                    <KanbanColumn
                        key={col.id}
                        col={col}
                        ids={columnItems[col.id] ?? []}
                        itemsById={itemsById}
                        renderCard={renderCard}
                        reduce={reduce}
                        emptyHint={emptyHint}
                        countLabel={countLabel}
                    />
                ))}
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
    emptyHint,
    countLabel,
}: {
    col: KanbanColumnDef;
    ids: number[];
    itemsById: Map<number, T>;
    renderCard: (item: T) => ReactNode;
    reduce: boolean;
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
                                <SortableCard key={id} id={id} reduce={reduce}>
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

function SortableCard({ id, reduce, children }: { id: number; reduce: boolean; children: ReactNode }) {
    const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });
    const style = {
        transform: CSS.Transform.toString(transform),
        transition: reduce ? undefined : transition,
    };
    return (
        <li
            ref={setNodeRef}
            style={style}
            {...attributes}
            {...listeners}
            className={`list-none touch-none outline-none ${isDragging ? 'opacity-40' : ''}`}
        >
            {children}
        </li>
    );
}
