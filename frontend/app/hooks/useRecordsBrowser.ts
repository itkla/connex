'use client';

import { useEffect, useMemo, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import {
    type DisplayMode,
    type FilterState,
    type SelectableDisplayMode,
    type SelectionId,
    isSelectableDisplayMode,
} from '../components/records/types';
import { PEEK_PARAM } from './useRecordPeek';
import { SERVER_RECORDS_URL_KEYS } from './useServerRecords';
import { parseListQuery, SAVED_VIEW_URL_KEY } from './listStateUrl';
import { useActions } from './useActions';
import { useIsMobile } from './useIsMobile';
import { useWorkspace } from './useWorkspace';

/** Query keys the browser writer must never treat as a facet filter or wipe: the view mode, the peek
 * deep link, the saved-view pointer ({@link SAVED_VIEW_URL_KEY}), and the server-list state
 * ({@link SERVER_RECORDS_URL_KEYS}) owned by other writers. */
const RESERVED_PARAM_KEYS = new Set<string>(['view', PEEK_PARAM, SAVED_VIEW_URL_KEY, ...SERVER_RECORDS_URL_KEYS]);

const VIEW_PREFIX = 'connex:view:';

/**
 * Scopes the persisted view preference to the active user + workspace, matching
 * {@link useRecordDensity}, so the choice survives sessions without leaking between accounts sharing a
 * browser.
 */
function viewStorageKey(storageKey: string, userId: number | null, workspaceId: number | null): string {
    return `${VIEW_PREFIX}${userId ?? 'anon'}:${workspaceId ?? 'none'}:${storageKey}`;
}

interface UseRecordsBrowserOptions<T extends { id: SelectionId }> {
    items: T[];
    storageKey: string;
    searchFields: (item: T) => (string | undefined | null)[];
    initialDisplayMode?: SelectableDisplayMode;
    restoreUrlQuery?: boolean;
}

/**
 * Manages the display mode, query, filters, and selection for a list of records.
 *
 * Display mode is deliberately two values. `displayMode` is the user's *preference*: it is the only one
 * persisted to `localStorage` and mirrored into the `view` query param, and it can only ever hold a
 * {@link SelectableDisplayMode}. `effectiveDisplayMode` is what should actually be rendered — below the
 * `md` breakpoint it is forced to `list` so phones get a row layout instead of a horizontally scrolling
 * desktop table. Because the force never writes back, resizing to a phone and back leaves the desktop
 * preference untouched.
 *
 * @param items - the list of records
 * @param storageKey - the per-entity suffix for the persisted view preference
 * @param searchFields - the fields to search for in the records
 * @param initialDisplayMode - the mode to use before a stored preference is read
 * @param restoreUrlQuery - whether to initialize the query from the URL's sanitized `q` value
 */
export function useRecordsBrowser<T extends { id: SelectionId }>(
    {
        items,
        storageKey,
        searchFields,
        initialDisplayMode = 'table',
        restoreUrlQuery = false,
    }: UseRecordsBrowserOptions<T>,
) {
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const isMobile = useIsMobile();
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const scopedStorageKey = viewStorageKey(storageKey, context.user?.id ?? null, activeWorkspaceId);

    const urlView = searchParams.get('view');
    const [displayMode, setDisplayMode] = useState<SelectableDisplayMode>(
        isSelectableDisplayMode(urlView) ? urlView : initialDisplayMode,
    );
    const [initialized, setInitialized] = useState(false);
    const [query, setQuery] = useState(() => (
        restoreUrlQuery ? parseListQuery(searchParams.get('q')) : ''
    ));
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
            let stored: string | null = null;
            try {
                stored = window.localStorage.getItem(scopedStorageKey);
            } catch {
                stored = null;
            }
            // eslint-disable-next-line react-hooks/set-state-in-effect
            if (isSelectableDisplayMode(stored)) setDisplayMode(stored);
        }
        setInitialized(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scopedStorageKey]);

    useEffect(() => {
        if (!initialized) return;
        try {
            window.localStorage.setItem(scopedStorageKey, displayMode);
        } catch {}
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
    }, [displayMode, filterState, initialized, pathname, scopedStorageKey, searchParams]);

    const effectiveDisplayMode: DisplayMode = isMobile ? 'list' : displayMode;

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
        effectiveDisplayMode,
        isMobile,
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
