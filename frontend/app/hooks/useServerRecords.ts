'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import type { Page, PageParams } from '@/app/lib/types';
import { ApiError } from '@/app/lib/api';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { MAX_URL_PAGE_SIZE, parseListInt, writeListStateToUrl, type SortDir } from '@/app/hooks/listStateUrl';

export type { SortDir };
export { SERVER_RECORDS_URL_KEYS } from '@/app/hooks/listStateUrl';

type RecordsState<T> = Page<T> & {
    workspaceId: number | null;
    requestKey: string | null;
    loading: boolean;
    error: Error | null;
};

/**
 * Server-side pagination hook. When `urlSync` is true, its query/sort/page/size are seeded from and
 * reflected into the URL via shallow `history.replaceState` (the #405 records-browser contract):
 * it only ever touches its own {@link SERVER_RECORDS_URL_KEYS}, preserving `view`/filter/`peek` params
 * owned by other writers, and it resets to defaults on a workspace switch so state never leaks across
 * workspaces. Leave `urlSync` off for consumers that own the URL themselves (e.g. FilesBrowser).
 *
 * A consumer that owns the URL itself can still restore list state on first render by passing
 * `seedQuery` / `seedPage` / `seedSize`, which avoids seeding from an effect and refetching once the
 * values land. That path is for browsers whose `sort` value space conflicts with the records-browser
 * `sort`/`dir` pair — they write pagination through their own owned-params writer instead of
 * enabling full `urlSync`.
 * `requestContext` refreshes server-owned windows (such as a profile calendar day) without sending a client date.
 * Set `retainPreviousData` false when a changed filter must hide the previous selection immediately.
 * Retryable failures retain the last successful page in the same workspace and expose `error` until a successful retry.
 * Authentication and permission failures discard that page so later outages cannot reveal denied rows again.
 * Consumers must handle expired sessions and access denials separately, never showing empty-state copy for a failure.
 *
 * @param fetcher - loads a page of results for the given params
 * @param extraParams - non-paging params merged into every fetch
 * @param options - `defaultSize`, optional initial query/page/size seeds, and whether to sync list state to the URL
 */
export function useServerRecords<T, P extends PageParams = PageParams>(
    fetcher: (params: P) => Promise<Page<T>>,
    extraParams: Omit<P, keyof PageParams> = {} as Omit<P, keyof PageParams>,
    options: {
        defaultSize?: number;
        urlSync?: boolean;
        retainPreviousData?: boolean;
        requestContext?: string;
        seedQuery?: string;
        seedPage?: string;
        seedSize?: string;
    } = {},
) {
    const { defaultSize = 25, urlSync = false, seedQuery, seedPage, seedSize, retainPreviousData = true, requestContext = '' } = options;
    const searchParams = useSearchParams();
    const pathname = usePathname();
    const { activeWorkspaceId } = useWorkspace();

    const seed = urlSync ? searchParams : null;
    const seededQuery = (urlSync ? seed?.get('q') : seedQuery)?.trim() ?? '';
    const seededPageParam = (urlSync ? seed?.get('page') : seedPage) ?? null;
    const seededSizeParam = (urlSync ? seed?.get('size') : seedSize) ?? null;

    const [records, setRecords] = useState<RecordsState<T>>({
        workspaceId: activeWorkspaceId, requestKey: null, items: [], total: 0, loading: true, error: null,
    });
    const currentWorkspace = records.workspaceId === activeWorkspaceId;
    const [page, setPage] = useState(() => parseListInt(seededPageParam, 1));
    const [size, setSize] = useState(() => parseListInt(seededSizeParam, defaultSize, Math.max(defaultSize, MAX_URL_PAGE_SIZE)));
    const [query, setQuery] = useState(seededQuery);
    const [debouncedQuery, setDebouncedQuery] = useState(seededQuery);
    const [sortKey, setSortKey] = useState<string | null>(() => seed?.get('sort') || null);
    const [sortDirection, setSortDirection] = useState<SortDir>(() => (seed?.get('dir') === 'desc' ? 'desc' : 'asc'));
    const [revision, setRevision] = useState(0);

    const extraKey = JSON.stringify(extraParams);
    const requestKey = JSON.stringify([page, size, debouncedQuery, sortKey, sortDirection, extraKey, requestContext]);
    const currentRequest = currentWorkspace && (retainPreviousData || records.requestKey === requestKey);
    const items = currentRequest ? records.items : [];
    const total = currentRequest ? records.total : 0;
    const loading = !currentRequest || records.loading;
    const error = currentRequest ? records.error : null;

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

    const resetKey = JSON.stringify([extraKey, requestContext]);
    const prevExtraKeyRef = useRef(resetKey);
    useEffect(() => {
        if (prevExtraKeyRef.current === resetKey) return;
        prevExtraKeyRef.current = resetKey;
        setPage(1);
    }, [resetKey]);

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
        setRecords((previous) => previous.workspaceId === activeWorkspaceId
            && (retainPreviousData || previous.requestKey === requestKey)
            ? { ...previous, loading: true }
            : { workspaceId: activeWorkspaceId, requestKey, items: [], total: 0, loading: true, error: null });
        fetcher({ page, size, q: debouncedQuery || undefined, sort: sortKey ?? undefined, dir: sortDirection, ...JSON.parse(extraKey) } as P)
            .then((res) => {
                if (!active) return;
                setRecords({ ...res, workspaceId: activeWorkspaceId, requestKey, loading: false, error: null });
                const maxPage = Math.max(1, Math.ceil(res.total / size));
                if (page > maxPage) setPage(maxPage);
            })
            .catch((failure: unknown) => {
                const denied = failure instanceof ApiError && (failure.status === 401 || failure.status === 403);
                if (active) setRecords((previous) => ({
                    ...previous,
                    items: denied ? [] : previous.items,
                    total: denied ? 0 : previous.total,
                    error: failure instanceof Error ? failure : new Error('Record list request failed'),
                }));
            })
            .finally(() => { if (active) setRecords((previous) => ({ ...previous, loading: false })); });
        return () => { active = false; };
    }, [fetcher, page, size, debouncedQuery, sortKey, sortDirection, extraKey, activeWorkspaceId, requestKey, retainPreviousData]);

    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => load(), [load, revision]);

    const reload = useCallback(() => setRevision((value) => value + 1), []);

    const patchItem = useCallback((id: number, partial: Partial<T>) => {
        setRecords((previous) => previous.workspaceId !== activeWorkspaceId ? previous : {
            ...previous,
            items: previous.items.map((item) => (
                typeof item === 'object' && item !== null && 'id' in item && item.id === id
                    ? { ...item, ...partial }
                    : item
            )),
        });
    }, [activeWorkspaceId]);

    /** Replaces a complete server snapshot so omitted nullable fields cannot survive an acknowledged save. */
    const replaceItem = useCallback((id: number, saved: T) => {
        setRecords((previous) => previous.workspaceId !== activeWorkspaceId ? previous : {
            ...previous,
            items: previous.items.map((item) => (
                typeof item === 'object' && item !== null && 'id' in item && item.id === id ? saved : item
            )),
        });
    }, [activeWorkspaceId]);

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
        items, total, loading, error,
        page, setPage,
        size, setSize: changeSize,
        query, setQuery, applyQuery,
        sortKey, sortDirection, onSortChange, applySort,
        revision, reload, patchItem, replaceItem,
    };
}
