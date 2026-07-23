'use client';

import { useEffect, useRef } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { parseListQuery, writeListQueryToUrl } from '@/app/hooks/listStateUrl';

/**
 * Synchronizes the Deals query owner with `q` while guarding a newer same-mounted URL navigation from
 * a stale deferred write. External queries reset paging and selection before becoming authoritative.
 */
export default function DealsQueryUrlSync({
    query,
    deferredQuery,
    onExternalQuery,
}: {
    query: string;
    deferredQuery: string;
    onExternalQuery: (query: string) => void;
}) {
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const urlQueryParam = searchParams.get('q');
    const lastObservedUrlQueryRef = useRef<string | null>(urlQueryParam);
    const pendingExternalQueryRef = useRef<{ value: string; previous: string } | null>(null);

    useEffect(() => {
        if (urlQueryParam === lastObservedUrlQueryRef.current) return;
        lastObservedUrlQueryRef.current = urlQueryParam;
        const pending = { value: parseListQuery(urlQueryParam), previous: query.trim() };
        pendingExternalQueryRef.current = pending;
        const timer = window.setTimeout(() => {
            if (pendingExternalQueryRef.current !== pending) return;
            onExternalQuery(pending.value);
        }, 0);
        return () => window.clearTimeout(timer);
    }, [onExternalQuery, query, urlQueryParam]);

    useEffect(() => {
        const pending = pendingExternalQueryRef.current;
        const normalizedQuery = query.trim();
        if (pending && normalizedQuery !== pending.previous && normalizedQuery !== pending.value) {
            pendingExternalQueryRef.current = null;
        }
    }, [query]);

    useEffect(() => {
        const pending = pendingExternalQueryRef.current;
        if (pending && pending.value !== deferredQuery) return;
        pendingExternalQueryRef.current = null;
        lastObservedUrlQueryRef.current = deferredQuery || null;
        writeListQueryToUrl(pathname, deferredQuery);
    }, [deferredQuery, pathname, urlQueryParam]);

    return null;
}
