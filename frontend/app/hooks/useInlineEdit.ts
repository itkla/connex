'use client';

import { useCallback, useMemo, useRef, useState } from 'react';

/**
 * Optimistic per-row field overrides for inline table editing, kept OUT of the server data snapshot so a
 * save never triggers a list refetch (scroll and selection are preserved). Each edited field is applied
 * immediately and reverted if its save rejects. Because the record update endpoints replace the whole
 * object (omitted fields are cleared), {@link InlineEdit.commit} hands the saver a full record merged from
 * the row + every prior override, so editing one field never clobbers another edited in the same session.
 */
export interface InlineEdit<T> {
    value: <K extends keyof T>(item: T, field: K) => T[K];
    commit: <K extends keyof T>(item: T, field: K, next: T[K], save: (full: T) => Promise<void>) => Promise<void>;
}

export function useInlineEdit<T extends { id: number }>(): InlineEdit<T> {
    const [, forceRender] = useState(0);
    const overridesRef = useRef<Record<number, Partial<T>>>({});

    const bump = useCallback(() => forceRender((n) => n + 1), []);

    const value = useCallback(<K extends keyof T>(item: T, field: K): T[K] => {
        const row = overridesRef.current[item.id];
        return row && field in row ? (row[field] as T[K]) : item[field];
    }, []);

    const commit = useCallback(
        async <K extends keyof T>(item: T, field: K, next: T[K], save: (full: T) => Promise<void>) => {
            const rowBefore = overridesRef.current[item.id];
            const had = rowBefore ? field in rowBefore : false;
            const previous = rowBefore?.[field];
            overridesRef.current = { ...overridesRef.current, [item.id]: { ...rowBefore, [field]: next } };
            bump();
            const full = { ...item, ...overridesRef.current[item.id] } as T;
            try {
                await save(full);
            } catch (error) {
                const row = { ...overridesRef.current[item.id] };
                if (had) row[field] = previous as T[K];
                else delete row[field];
                overridesRef.current = { ...overridesRef.current, [item.id]: row };
                bump();
                throw error;
            }
        },
        [bump],
    );

    return useMemo(() => ({ value, commit }), [value, commit]);
}
