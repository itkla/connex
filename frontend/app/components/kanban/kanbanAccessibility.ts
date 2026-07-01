import type { Announcements, ScreenReaderInstructions, UniqueIdentifier } from '@dnd-kit/core';

type Translator = (key: string, values?: Record<string, string>) => string;

/**
 * Builds localized drag-and-drop screen-reader announcements and instructions for a
 * {@link KanbanBoard}. The translator's namespace must define the {@code a11yLifted / a11yOver /
 * a11yDropped / a11yCancelled / a11yInstructions} keys. Shared by the deals and tasks boards.
 * @param t the board namespace translator
 * @param itemName resolves a dragged item id to its display name
 * @param columnName resolves an over-target id (card or column) to its column label
 */
export function kanbanAccessibility(
    t: Translator,
    itemName: (id: UniqueIdentifier) => string,
    columnName: (id: UniqueIdentifier) => string,
): { announcements: Announcements; screenReaderInstructions: ScreenReaderInstructions } {
    return {
        announcements: {
            onDragStart: ({ active }) => t('a11yLifted', { name: itemName(active.id) }),
            onDragOver: ({ active, over }) =>
                over ? t('a11yOver', { name: itemName(active.id), column: columnName(over.id) }) : undefined,
            onDragEnd: ({ active, over }) =>
                over
                    ? t('a11yDropped', { name: itemName(active.id), column: columnName(over.id) })
                    : t('a11yCancelled', { name: itemName(active.id) }),
            onDragCancel: ({ active }) => t('a11yCancelled', { name: itemName(active.id) }),
        },
        screenReaderInstructions: { draggable: t('a11yInstructions') },
    };
}
