import type { Announcements, Over, ScreenReaderInstructions, UniqueIdentifier } from '@dnd-kit/core';

type Translator = (key: string, values?: Record<string, string>) => string;

/** Resolves a drop target through its live sortable container, including a reparented active card. */
export function kanbanColumnId(over: Over): string | undefined {
    const sortable: unknown = over.data.current?.sortable;
    const containerId = typeof sortable === 'object' && sortable !== null && 'containerId' in sortable
        ? sortable.containerId
        : over.id;
    return typeof containerId === 'string' && containerId.startsWith('col:') ? containerId.slice(4) : undefined;
}

/**
 * Builds localized drag-and-drop screen-reader announcements and instructions for a
 * {@link KanbanBoard}. The translator's namespace must define the {@code a11yLifted / a11yOver /
 * a11yMoveRequested / a11yRestored / a11yCancelled / a11yInstructions} keys. Shared by the deals and tasks boards.
 * @param t the board namespace translator
 * @param itemName resolves a dragged item id to its display name
 * @param columnName resolves a live column id to its label
 * @param dropped optional announcement for a requested move that still needs confirmation or saving
 */
export function kanbanAccessibility(
    t: Translator,
    itemName: (id: UniqueIdentifier) => string,
    columnName: (id: string) => string,
    dropped?: (id: UniqueIdentifier, columnId: string) => string,
): {
    announcements: Announcements;
    screenReaderInstructions: ScreenReaderInstructions;
    moveRestored: (itemId: number, columnId: string, position: number) => string;
} {
    const destination = (over: Over | null) => over ? kanbanColumnId(over) : undefined;
    return {
        announcements: {
            onDragStart: ({ active }) => t('a11yLifted', { name: itemName(active.id) }),
            onDragOver: ({ active, over }) => {
                const column = destination(over);
                return column === undefined ? undefined : t('a11yOver', { name: itemName(active.id), column: columnName(column) });
            },
            onDragEnd: ({ active, over }) => {
                const column = destination(over);
                return column === undefined
                    ? t('a11yCancelled', { name: itemName(active.id) })
                    : dropped?.(active.id, column) ?? t('a11yMoveRequested', { name: itemName(active.id), column: columnName(column) });
            },
            onDragCancel: ({ active }) => t('a11yCancelled', { name: itemName(active.id) }),
        },
        screenReaderInstructions: { draggable: t('a11yInstructions') },
        moveRestored: (id, column, position) => t('a11yRestored', { name: itemName(id), column: columnName(column), position: String(position + 1) }),
    };
}
