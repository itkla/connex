'use client';

import { type ReactNode, useState } from 'react';
import { Bars3Icon } from '@heroicons/react/24/outline';
import {
    DndContext,
    DragOverlay,
    KeyboardSensor,
    PointerSensor,
    closestCenter,
    useSensor,
    useSensors,
    type Announcements,
    type DragEndEvent,
} from '@dnd-kit/core';
import {
    SortableContext,
    arrayMove,
    rectSortingStrategy,
    sortableKeyboardCoordinates,
    useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

import { cn } from '@/lib/utils';

export type SortableGridItem = {
    id: string;
};

export type SortableGridRenderState = {
    dragHandle: ReactNode;
    isDragging: boolean;
};

export type SortableGridMessages = {
    instructions: string;
    handleLabel: (name: string) => string;
    lifted: (name: string) => string;
    over: (name: string, target: string) => string;
    dropped: (name: string, target: string) => string;
    cancelled: (name: string) => string;
};

export default function SortableGrid<T extends SortableGridItem>({
    items,
    getLabel,
    renderItem,
    renderOverlay,
    onChange,
    onActiveIdChange,
    messages,
    gridClassName,
    itemClassName,
    disabled = false,
    reduceMotion = false,
}: {
    items: T[];
    getLabel: (item: T) => string;
    renderItem: (item: T, state: SortableGridRenderState) => ReactNode;
    renderOverlay?: (item: T) => ReactNode;
    onChange: (items: T[]) => void;
    onActiveIdChange?: (id: string | null) => void;
    messages: SortableGridMessages;
    gridClassName?: string;
    itemClassName?: (item: T) => string | undefined;
    disabled?: boolean;
    reduceMotion?: boolean;
}) {
    const [activeId, setActiveId] = useState<string | null>(null);
    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );
    const itemById = new Map(items.map((item) => [item.id, item]));
    const nameFor = (id: string | number) => {
        const item = itemById.get(String(id));
        return item ? getLabel(item) : '';
    };
    const onDragEnd = (event: DragEndEvent) => {
        setActiveId(null);
        onActiveIdChange?.(null);
        const { active, over } = event;
        if (!over || active.id === over.id) return;
        const oldIndex = items.findIndex((item) => item.id === active.id);
        const newIndex = items.findIndex((item) => item.id === over.id);
        if (oldIndex < 0 || newIndex < 0) return;
        onChange(arrayMove(items, oldIndex, newIndex));
    };
    const announcements: Announcements = {
        onDragStart: ({ active }) => messages.lifted(nameFor(active.id)),
        onDragOver: ({ active, over }) =>
            over ? messages.over(nameFor(active.id), nameFor(over.id)) : undefined,
        onDragEnd: ({ active, over }) =>
            over
                ? messages.dropped(nameFor(active.id), nameFor(over.id))
                : messages.cancelled(nameFor(active.id)),
        onDragCancel: ({ active }) => messages.cancelled(nameFor(active.id)),
    };
    const activeItem = activeId ? itemById.get(activeId) : undefined;

    return (
        <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={(event) => {
                const id = String(event.active.id);
                setActiveId(id);
                onActiveIdChange?.(id);
            }}
            onDragEnd={onDragEnd}
            onDragCancel={() => {
                setActiveId(null);
                onActiveIdChange?.(null);
            }}
            accessibility={{ announcements, screenReaderInstructions: { draggable: messages.instructions } }}
        >
            <SortableContext items={items.map((item) => item.id)} strategy={rectSortingStrategy}>
                <div className={gridClassName}>
                    {items.map((item) => (
                        <SortableGridCell
                            key={item.id}
                            item={item}
                            label={messages.handleLabel(getLabel(item))}
                            disabled={disabled}
                            reduceMotion={reduceMotion}
                            className={itemClassName?.(item)}
                            render={renderItem}
                        />
                    ))}
                </div>
            </SortableContext>
            {renderOverlay ? (
                <DragOverlay>
                    {activeItem ? renderOverlay(activeItem) : null}
                </DragOverlay>
            ) : null}
        </DndContext>
    );
}

function SortableGridCell<T extends SortableGridItem>({
    item,
    label,
    disabled,
    reduceMotion,
    className,
    render,
}: {
    item: T;
    label: string;
    disabled: boolean;
    reduceMotion: boolean;
    className?: string;
    render: (item: T, state: SortableGridRenderState) => ReactNode;
}) {
    const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } =
        useSortable({ id: item.id, disabled });
    const dragHandle = disabled ? null : (
        <button
            type="button"
            ref={setActivatorNodeRef}
            aria-label={label}
            className="cursor-grab touch-none rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:cursor-grabbing motion-reduce:transition-none"
            {...attributes}
            {...listeners}
        >
            <Bars3Icon className="size-4" aria-hidden />
        </button>
    );

    return (
        <div
            ref={setNodeRef}
            className={cn('min-w-0', className)}
            style={{
                transform: CSS.Transform.toString(transform),
                transition: reduceMotion ? undefined : transition,
            }}
        >
            {render(item, { dragHandle, isDragging })}
        </div>
    );
}
