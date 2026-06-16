'use client';

import { useCallback, useMemo, useState } from 'react';

type SortDirection = 'asc' | 'desc';

export function useRecordsSort(initialKey: string | null = null) {
    const [sortKey, setSortKey] = useState<string | null>(initialKey);
    const [sortDirection, setSortDirection] = useState<SortDirection>('asc');

    const onSortChange = useCallback((key: string) => {
        setSortKey((prevKey) => {
            if (prevKey === key) {
                setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
                return prevKey;
            }
            setSortDirection('asc');
            return key;
        });
    }, []);

    const sortState = useMemo(
        () => ({ key: sortKey, direction: sortDirection, onSortChange }),
        [sortKey, sortDirection, onSortChange],
    );

    return { sortKey, sortDirection, onSortChange, sortState };
}