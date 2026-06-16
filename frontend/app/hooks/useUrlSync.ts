'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';

/**
 * Reflects a flat set of list params into the URL query string so a filtered /
 * sorted / paged view is shareable, back-button friendly, and survives a refresh.
 *
 * Single writer: pass the *complete* set of params the page owns; empty / falsy
 * values are dropped. History is replaced (no new entry) and the page never scrolls.
 * Reading the initial values back is left to the caller (e.g. `useState` initializers
 * off `useSearchParams`), since each page maps URL params into its own state shape.
 *
 * @example
 * useUrlSync({
 *   q: query || undefined,
 *   sort: sort !== 'newest' ? sort : undefined,
 *   page: page > 1 ? String(page) : undefined,
 * });
 * @param params - The parameters to sync with the URL.
 */
export function useUrlSync(params: Record<string, string | undefined>) {
    const router = useRouter();
    const pathname = usePathname();

    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value) search.set(key, value);
    }
    const qs = search.toString();

    useEffect(() => {
        router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    }, [qs, pathname, router]);
}