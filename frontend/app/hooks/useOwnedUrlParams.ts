'use client';

import { useEffect, useMemo } from 'react';
import { usePathname } from 'next/navigation';

import { writeOwnedParamsToUrl } from './listStateUrl';

/**
 * Reflects a page's own list params into the URL so a filtered / deep-linked view is shareable,
 * back-button friendly, and survives a refresh.
 *
 * Unlike a whole-query writer, this is non-destructive: it declares the keys the caller owns and only
 * ever set/deletes those, leaving every other param intact. Pass the *complete* set of owned keys —
 * `undefined` values are deleted, which is what clears a closed deep link. Reading the initial values
 * back is left to the caller (e.g. `useState` initializers off `useSearchParams`), since each page maps
 * URL params into its own state shape.
 *
 * Pages that resolve a deep-linked record asynchronously on mount must hold `ready` false until that
 * resolution settles, otherwise the first write would delete the very param it is about to restore.
 *
 * @param params - the complete set of keys this page owns, mapped to their current values
 * @param ready - whether the page's state is settled enough to author the URL
 */
export function useOwnedUrlParams(
    params: Readonly<Record<string, string | undefined>>,
    ready = true,
): void {
    const pathname = usePathname();
    const encoded = new URLSearchParams(
        Object.entries(params).map(([key, value]) => [key, value ?? '']),
    ).toString();

    const owned = useMemo(() => {
        const next: Record<string, string | undefined> = {};
        for (const [key, value] of new URLSearchParams(encoded)) next[key] = value || undefined;
        return next;
    }, [encoded]);

    useEffect(() => {
        if (!ready) return;
        writeOwnedParamsToUrl(pathname, owned);
    }, [owned, pathname, ready]);
}
