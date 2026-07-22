'use client';

import { useCallback, useMemo } from 'react';

/**
 * Commits an inline table edit. The edit is written straight into the server-record snapshot via
 * {@link patchItem} (the single source of truth — so quick-edit sheets, cards, the peek drawer and every
 * other full-replace save read the fresh value), applied optimistically and reverted if the save rejects.
 * Because the record update endpoints replace the whole object, {@link InlineEdit.commit} hands the saver
 * the full record merged from the snapshot row (which already carries any earlier edits) plus the new
 * field, so one field's save never clobbers or nulls another.
 */
export interface InlineEdit<T> {
    commit: <K extends keyof T>(item: T, field: K, next: T[K], save: (full: T) => Promise<void>) => Promise<void>;
}

export function useInlineEdit<T extends { id: number }>(
    patchItem: (id: number, partial: Partial<T>) => void,
): InlineEdit<T> {
    const commit = useCallback(
        async <K extends keyof T>(item: T, field: K, next: T[K], save: (full: T) => Promise<void>) => {
            const previous = item[field];
            const applied: Partial<T> = {};
            applied[field] = next;
            patchItem(item.id, applied);
            try {
                await save({ ...item, [field]: next });
            } catch (error) {
                const reverted: Partial<T> = {};
                reverted[field] = previous;
                patchItem(item.id, reverted);
                throw error;
            }
        },
        [patchItem],
    );

    return useMemo(() => ({ commit }), [commit]);
}
