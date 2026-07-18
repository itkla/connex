'use client';

import { useEffect, useMemo, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { type DisplayMode, type FilterState, type SelectionId, isDisplayMode } from '../components/records/types';
import { PEEK_PARAM } from './useRecordPeek';

interface UseRecordsBrowserOptions<T extends { id: SelectionId }> {
    items: T[];
    storageKey: string;
    searchFields: (item: T) => (string | undefined | null)[];
    initialDisplayMode?: DisplayMode;
}

/**
 * a hook to manage the display mode and query for a list of records
 * @param items - the list of records
 * @param storageKey - the key to store the display mode in localStorage
 * @param searchFields - the fields to search for in the records
 * @param initialDisplayMode - the initial display mode to use
 * @returns 
 */
export function useRecordsBrowser<T extends { id: SelectionId }>(
    { items, storageKey, searchFields, initialDisplayMode = 'table' }: UseRecordsBrowserOptions<T>,
) {
    const router = useRouter();
    const pathname = usePathname();
    const searchParams = useSearchParams();

    const urlView = searchParams.get('view');
    const [displayMode, setDisplayMode] = useState<DisplayMode>(
        isDisplayMode(urlView) ? urlView : initialDisplayMode,
    );
    const [initialized, setInitialized] = useState(false);
    const [query, setQuery] = useState('');
    const [filterState, setFilterState] = useState<FilterState>(() => {
        const state: FilterState = {};
        searchParams.forEach((value, key) => {
            if (key !== 'view' && key !== PEEK_PARAM && value) state[key] = value.split(',');
        });
        return state;
    });
    const [selectedIds, setSelectedIds] = useState<Set<SelectionId>>(new Set());
    const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

    useEffect(() => {
        if (!urlView) {
            const stored = window.localStorage.getItem(storageKey);
            // eslint-disable-next-line react-hooks/set-state-in-effect
            if (isDisplayMode(stored)) setDisplayMode(stored);
        }
        setInitialized(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (!initialized) return;
        window.localStorage.setItem(storageKey, displayMode);
        const params = new URLSearchParams(searchParams.toString());
        params.set('view', displayMode);
        for (const key of Array.from(params.keys())) {
            if (key !== 'view' && key !== PEEK_PARAM) params.delete(key);
        }
        for (const [key, values] of Object.entries(filterState)) {
            if (values.length) params.set(key, values.join(','));
        }
        const next = params.toString();
        if (next === searchParams.toString()) return;
        router.replace(`${pathname}?${next}`, { scroll: false });
    }, [displayMode, filterState, initialized, pathname, router, searchParams, storageKey]);

    const filteredItems = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return items;
        return items.filter((item) =>
            searchFields(item).some((field) => field?.toLowerCase().includes(q)),
        );
    }, [items, query, searchFields]);

    const selectedItems = useMemo(
        () => items.filter((item) => selectedIds.has(item.id)),
        [items, selectedIds],
    );

    return {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        filteredItems,
        selectedItems,
        deleteDialogOpen,
        setDeleteDialogOpen,
    };
}
