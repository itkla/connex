'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import type { Page, PageParams } from '@/app/lib/types';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { MAX_URL_PAGE_SIZE, parseListInt, writeListStateToUrl, type SortDir } from '@/app/hooks/listStateUrl';

export type { SortDir };
export { SERVER_RECORDS_URL_KEYS } from '@/app/hooks/listStateUrl';

/**
 * Server-side pagination hook. When `urlSync` is true, its query/sort/page/size are seeded from and
 * reflected into the URL via shallow `history.replaceState` (the #405 records-browser contract):
 * it only ever touches its own {@link SERVER_RECORDS_URL_KEYS}, preserving `view`/filter/`peek` params
 * owned by other writers, and it resets to defaults on a workspace switch so state never leaks across
 * workspaces. Leave `urlSync` off for consumers that own the URL themselves (e.g. FilesBrowser).
 *
 * A consumer that owns the URL itself can still restore the search box on first render by passing
 * `seedQuery`, which avoids seeding it from an effect and refetching once the value lands.
 *
 * @param fetcher - loads a page of results for the given params
 * @param extraParams - non-paging params merged into every fetch
 * @param options - `defaultSize`, an optional initial query, and whether to sync list state to the URL
 */
export function useServerRecords<T, P extends PageParams = PageParams>(
    fetcher: (params: P) => Promise<Page<T>>,
    extraParams: Omit<P, keyof PageParams> = {} as Omit<P, keyof PageParams>,
    options: { defaultSize?: number; urlSync?: boolean; seedQuery?: string } = {},
) {
    const { defaultSize = 25, urlSync = false, seedQuery } = options;
    const searchParams = useSearchParams();
    const pathname = usePathname();
    const { activeWorkspaceId } = useWorkspace();

    const seed = urlSync ? searchParams : null;
    const seededQuery = (urlSync ? seed?.get('q') : seedQuery)?.trim() ?? '';

    const [items, setItems] = useState<T[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [page, setPage] = useState(() => parseListInt(seed?.get('page') ?? null, 1));
    const [size, setSize] = useState(() => parseListInt(seed?.get('size') ?? null, defaultSize, Math.max(defaultSize, MAX_URL_PAGE_SIZE)));
    const [query, setQuery] = useState(seededQuery);
    const [debouncedQuery, setDebouncedQuery] = useState(seededQuery);
    const [sortKey, setSortKey] = useState<string | null>(() => seed?.get('sort') || null);
    const [sortDirection, setSortDirection] = useState<SortDir>(() => (seed?.get('dir') === 'desc' ? 'desc' : 'asc'));
    const [revision, setRevision] = useState(0);

    const extraKey = JSON.stringify(extraParams);

    const debounceMountedRef = useRef(false);
    useEffect(() => {
        const isMount = !debounceMountedRef.current;
        debounceMountedRef.current = true;
        const id = setTimeout(() => {
            setDebouncedQuery(query.trim());
            if (!isMount) setPage(1);
        }, 250);
        return () => clearTimeout(id);
    }, [query]);

    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => { setPage(1); }, [extraKey]);

    useEffect(() => {
        if (!urlSync) return;
        writeListStateToUrl(pathname, { q: debouncedQuery, sort: sortKey, dir: sortDirection, page, size }, defaultSize);
    }, [urlSync, debouncedQuery, sortKey, sortDirection, page, size, defaultSize, pathname]);

    const workspaceRef = useRef(activeWorkspaceId);
    useEffect(() => {
        if (workspaceRef.current === activeWorkspaceId) return;
        workspaceRef.current = activeWorkspaceId;
        if (!urlSync) return;
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setPage(1); setSize(defaultSize); setQuery(''); setDebouncedQuery(''); setSortKey(null); setSortDirection('asc');
    }, [activeWorkspaceId, defaultSize, urlSync]);

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

    const patchItem = useCallback((id: number, partial: Partial<T>) => {
        setItems((prev) => prev.map((item) => ((item as { id?: number }).id === id ? { ...item, ...partial } : item)));
    }, []);

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
        revision, reload, patchItem,
    };
}
