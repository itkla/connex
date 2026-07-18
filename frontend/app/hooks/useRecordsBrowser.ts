'use client';

import { useEffect, useMemo, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { type DisplayMode, type FilterState, type SelectionId, isDisplayMode } from '../components/records/types';
import { PEEK_PARAM } from './useRecordPeek';
import { SERVER_RECORDS_URL_KEYS } from './useServerRecords';

/** Query keys the browser writer must never treat as a facet filter or wipe: the view mode, the peek
 * deep link, and the server-list state ({@link SERVER_RECORDS_URL_KEYS}) owned by other writers. */
const RESERVED_PARAM_KEYS = new Set<string>(['view', PEEK_PARAM, ...SERVER_RECORDS_URL_KEYS]);

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
            if (!RESERVED_PARAM_KEYS.has(key) && value) state[key] = value.split(',');
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
        const params = new URLSearchParams(window.location.search);
        params.set('view', displayMode);
        for (const key of Array.from(params.keys())) {
            if (!RESERVED_PARAM_KEYS.has(key)) params.delete(key);
        }
        for (const [key, values] of Object.entries(filterState)) {
            if (values.length) params.set(key, values.join(','));
        }
        const next = params.toString();
        if (next === window.location.search.replace(/^\?/, '')) return;
        window.history.replaceState(null, '', next ? `${pathname}?${next}` : pathname);
    }, [displayMode, filterState, initialized, pathname, searchParams, storageKey]);

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
