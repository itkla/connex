'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import type { Page, PageParams } from '@/app/lib/types';
import { useWorkspace } from '@/app/hooks/useWorkspace';

export type SortDir = 'asc' | 'desc';

/** URL query keys owned by {@link useServerRecords} when `urlSync` is on. Kept in one place so
 * {@link useRecordsBrowser} can preserve them rather than wiping them as stale filter params. */
export const SERVER_RECORDS_URL_KEYS = ['q', 'sort', 'dir', 'page', 'size'] as const;

function parsePositiveInt(value: string | null, fallback: number): number {
    if (value === null) return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

/**
 * Server-side pagination hook. When `urlSync` is true, its query/sort/page/size are seeded from and
 * reflected into the URL via shallow `history.replaceState` (the #405 records-browser contract):
 * it only ever touches its own {@link SERVER_RECORDS_URL_KEYS}, preserving `view`/filter/`peek` params
 * owned by other writers, and it resets to defaults on a workspace switch so state never leaks across
 * workspaces. Leave `urlSync` off for consumers that own the URL themselves (e.g. FilesBrowser).
 *
 * @param fetcher - loads a page of results for the given params
 * @param extraParams - non-paging params merged into every fetch
 * @param options - `defaultSize` and whether to sync list state to the URL
 */
export function useServerRecords<T, P extends PageParams = PageParams>(
    fetcher: (params: P) => Promise<Page<T>>,
    extraParams: Omit<P, keyof PageParams> = {} as Omit<P, keyof PageParams>,
    options: { defaultSize?: number; urlSync?: boolean } = {},
) {
    const { defaultSize = 25, urlSync = false } = options;
    const searchParams = useSearchParams();
    const pathname = usePathname();
    const { activeWorkspaceId } = useWorkspace();

    const seed = urlSync ? searchParams : null;
    const seededQuery = seed?.get('q')?.trim() ?? '';

    const [items, setItems] = useState<T[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [page, setPage] = useState(() => parsePositiveInt(seed?.get('page') ?? null, 1));
    const [size, setSize] = useState(() => parsePositiveInt(seed?.get('size') ?? null, defaultSize));
    const [query, setQuery] = useState(seededQuery);
    const [debouncedQuery, setDebouncedQuery] = useState(seededQuery);
    const [sortKey, setSortKey] = useState<string | null>(() => seed?.get('sort') || null);
    const [sortDirection, setSortDirection] = useState<SortDir>(() => (seed?.get('dir') === 'desc' ? 'desc' : 'asc'));
    const [revision, setRevision] = useState(0);

    const extraKey = JSON.stringify(extraParams);

    const debounceMountedRef = useRef(false);
    useEffect(() => {
        const id = setTimeout(() => {
            setDebouncedQuery(query.trim());
            if (debounceMountedRef.current) setPage(1);
            else debounceMountedRef.current = true;
        }, 250);
        return () => clearTimeout(id);
    }, [query]);

    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => { setPage(1); }, [extraKey]);

    useEffect(() => {
        if (!urlSync) return;
        const params = new URLSearchParams(window.location.search);
        const set = (key: string, value: string) => (value ? params.set(key, value) : params.delete(key));
        set('q', debouncedQuery);
        set('sort', sortKey ?? '');
        set('dir', sortKey && sortDirection === 'desc' ? 'desc' : '');
        set('page', page > 1 ? String(page) : '');
        set('size', size !== defaultSize ? String(size) : '');
        const next = params.toString();
        if (next === window.location.search.replace(/^\?/, '')) return;
        window.history.replaceState(null, '', next ? `${pathname}?${next}` : pathname);
    }, [urlSync, debouncedQuery, sortKey, sortDirection, page, size, defaultSize, pathname]);

    const workspaceRef = useRef(activeWorkspaceId);
    useEffect(() => {
        if (workspaceRef.current === activeWorkspaceId) return;
        workspaceRef.current = activeWorkspaceId;
        setPage(1); setSize(defaultSize); setQuery(''); setDebouncedQuery(''); setSortKey(null); setSortDirection('asc');
    }, [activeWorkspaceId, defaultSize]);

    const load = useCallback(() => {
        let active = true;
        setLoading(true);
        fetcher({ page, size, q: debouncedQuery || undefined, sort: sortKey ?? undefined, dir: sortDirection, ...JSON.parse(extraKey) } as P)
            .then((res) => {
                if (!active) return;
                setItems(res.items);
                setTotal(res.total);
                const maxPage = Math.max(1, Math.ceil(res.total / size));
                if (page > maxPage) setPage(maxPage);
            })
            .catch(() => { if (active) { setItems([]); setTotal(0); } })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, [fetcher, page, size, debouncedQuery, sortKey, sortDirection, extraKey]);

    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => load(), [load, revision]);

    const reload = useCallback(() => setRevision((value) => value + 1), []);

    const changeSize = useCallback((next: number) => { setSize(next); setPage(1); }, []);

    const onSortChange = useCallback((key: string) => {
        if (key === sortKey) setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
        else { setSortKey(key); setSortDirection('asc'); }
        setPage(1);
    }, [sortKey]);

    const applySort = useCallback((key: string | null, direction: SortDir) => {
        setSortKey(key);
        setSortDirection(direction);
        setPage(1);
    }, []);

    const applyQuery = useCallback((q: string) => {
        setQuery(q);
        setDebouncedQuery(q.trim());
        setPage(1);
    }, []);

    return {
        items, total, loading,
        page, setPage,
        size, setSize: changeSize,
        query, setQuery, applyQuery,
        sortKey, sortDirection, onSortChange, applySort,
        revision, reload,
    };
}
