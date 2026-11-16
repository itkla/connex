import type { KeyboardCoordinateGetter } from '@dnd-kit/core';
import { sortableKeyboardCoordinates } from '@dnd-kit/sortable';

export interface KanbanKeyboardLayout {
    columnIds: readonly string[];
    columnByItemId: ReadonlyMap<number, string>;
}

/**
 * Moves horizontally by rendered column order, excluding the current column's padding and cards.
 * The layout must reflect the live drag container even when the sensor retains its original callback.
 * Vertical movement keeps the sortable library's navigation within the board.
 */
export function kanbanKeyboardCoordinates(
    event: KeyboardEvent,
    args: Parameters<KeyboardCoordinateGetter>[1],
    layout: KanbanKeyboardLayout,
): ReturnType<KeyboardCoordinateGetter> {
    if (event.code !== 'ArrowLeft' && event.code !== 'ArrowRight') return sortableKeyboardCoordinates(event, args);
    event.preventDefault();
    const collision = args.context.collisionRect;
    const current = layout.columnByItemId.get(Number(args.active));
    if (!collision || current === undefined) return;
    const index = layout.columnIds.indexOf(current);
    if (index < 0) return;
    const adjacent = layout.columnIds[index + (event.code === 'ArrowRight' ? 1 : -1)];
    if (adjacent === undefined) return;
    const id = `col:${adjacent}`;
    const target = args.context.droppableContainers.get(id);
    const bounds = args.context.droppableRects.get(id);
    if (!target || target.disabled || !bounds) return;
    return {
        x: bounds.left + (bounds.width - collision.width) / 2,
        y: Math.max(bounds.top, Math.min(collision.top, bounds.bottom - collision.height)),
    };
}
