'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Page, PageParams } from '@/app/lib/types';

export type SortDir = 'asc' | 'desc';

/**
 * server-side pagination hook
 * @param fetcher 
 * @param extraParams 
 * @param defaultSize 
 * @returns 
 */
export function useServerRecords<T, P extends PageParams = PageParams>(
    fetcher: (params: P) => Promise<Page<T>>,
    extraParams: Omit<P, keyof PageParams> = {} as Omit<P, keyof PageParams>,
    defaultSize = 25,
) {
    const [items, setItems] = useState<T[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(defaultSize);
    const [query, setQuery] = useState('');
    const [debouncedQuery, setDebouncedQuery] = useState('');
    const [sortKey, setSortKey] = useState<string | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDir>('asc');

    const extraKey = JSON.stringify(extraParams);

    useEffect(() => {
        const id = setTimeout(() => { setDebouncedQuery(query.trim()); setPage(1); }, 250);
        return () => clearTimeout(id);
    }, [query]);

    // eslint-disable-next-line react-hooks/set-state-in-effect
    useEffect(() => { setPage(1); }, [extraKey]);

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
    useEffect(() => load(), [load]);

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

    return {
        items, total, loading,
        page, setPage,
        size, setSize: changeSize,
        query, setQuery,
        sortKey, sortDirection, onSortChange, applySort,
        reload: load,
    };
}