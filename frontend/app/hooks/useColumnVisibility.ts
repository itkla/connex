'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';

import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { ColumnDef } from '@/app/components/records/types';

const COLUMNS_PREFIX = 'connex:columns:';

function columnsStorageKey(entity: string, userId: number | null, workspaceId: number | null): string {
    return `${COLUMNS_PREFIX}${entity}:${userId ?? 'anon'}:${workspaceId ?? 'none'}`;
}

function parseHidden(raw: string | null): string[] {
    if (!raw) return [];
    try {
        const parsed: unknown = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : [];
    } catch {
        return [];
    }
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
    if (a.size !== b.size) return false;
    for (const value of a) {
        if (!b.has(value)) return false;
    }
    return true;
}

/** A single hideable column offered by the column-visibility control. */
export interface ColumnToggle {
    key: string;
    label: string;
    visible: boolean;
    locked: boolean;
}

/** Options for {@link useColumnVisibility}. */
export interface ColumnVisibilityOptions {
    /**
     * A column that must stay visible regardless of the stored preference — typically the active sort
     * column, so hiding it can never strand the table sorted by an invisible header the user can't reach.
     */
    lockedKey?: string | null;
}

/** The visible column subset plus the controls to change and persist it. */
export interface ColumnVisibility<T> {
    visibleColumns: ColumnDef<T>[];
    toggles: ColumnToggle[];
    setColumnVisible: (key: string, visible: boolean) => void;
    resetColumns: () => void;
    hiddenCount: number;
}

/**
 * Reads and persists which record-table columns the user has hidden, scoped to the active user + workspace
 * + entity so the choice survives sessions (localStorage) without leaking across accounts on a shared
 * browser. The first column is the record's identity — and the frozen column — so it is never hideable.
 * The stored set is read after mount to avoid a hydration mismatch.
 */
export function useColumnVisibility<T>(
    entity: string,
    columns: ColumnDef<T>[],
    options?: ColumnVisibilityOptions,
): ColumnVisibility<T> {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const lockedKey = options?.lockedKey ?? null;
    const key = columnsStorageKey(entity, context.user?.id ?? null, activeWorkspaceId);
    const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set());

    useEffect(() => {
        let stored: string | null = null;
        try {
            stored = window.localStorage.getItem(key);
        } catch {
            return;
        }
        const next = new Set(parseHidden(stored));
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setHidden((prev) => (sameSet(prev, next) ? prev : next));
    }, [key]);

    const primaryKey = columns.length > 0 ? columns[0].key : null;

    const persist = useCallback(
        (next: ReadonlySet<string>) => {
            setHidden(next);
            try {
                window.localStorage.setItem(key, JSON.stringify([...next]));
            } catch {}
        },
        [key],
    );

    const setColumnVisible = useCallback(
        (colKey: string, visible: boolean) => {
            if (colKey === primaryKey) return;
            const next = new Set(hidden);
            if (visible) {
                next.delete(colKey);
            } else {
                next.add(colKey);
            }
            persist(next);
        },
        [hidden, persist, primaryKey],
    );

    const resetColumns = useCallback(() => {
        persist(new Set());
    }, [persist]);

    const visibleColumns = useMemo(
        () =>
            columns.filter(
                (column) => column.key === primaryKey || column.key === lockedKey || !hidden.has(column.key),
            ),
        [columns, hidden, primaryKey, lockedKey],
    );

    const toggles = useMemo<ColumnToggle[]>(
        () =>
            columns
                .filter((column) => column.key !== primaryKey)
                .map((column) => {
                    const locked = column.key === lockedKey;
                    return { key: column.key, label: column.label, visible: locked || !hidden.has(column.key), locked };
                }),
        [columns, hidden, primaryKey, lockedKey],
    );

    const hiddenCount = toggles.reduce((count, toggle) => (toggle.visible ? count : count + 1), 0);

    return { visibleColumns, toggles, setColumnVisible, resetColumns, hiddenCount };
}
