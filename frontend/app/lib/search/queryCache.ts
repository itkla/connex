import type { SearchResults } from '@/app/lib/types';

/** One resolved search response, stamped so it can be revalidated once it ages out. */
export type CachedSearch = { data: SearchResults; fetchedAt: number };

/** Recently resolved searches, oldest key first, keyed by trimmed query. */
export type SearchCache = ReadonlyMap<string, CachedSearch>;

/** How many resolved queries the global search keeps before evicting the least recently used. */
export const SEARCH_CACHE_LIMIT = 24;

/** How long a resolved query is served without revalidating against the server. */
export const SEARCH_CACHE_TTL_MS = 30_000;

/**
 * Records a resolved response as the most recently used entry, evicting the least recently used
 * once the cache is over its limit.
 */
export function rememberSearch(
    cache: SearchCache,
    query: string,
    data: SearchResults,
    fetchedAt: number,
): SearchCache {
    const next = new Map(cache);
    next.delete(query);
    next.set(query, { data, fetchedAt });
    for (const oldest of next.keys()) {
        if (next.size <= SEARCH_CACHE_LIMIT) break;
        next.delete(oldest);
    }
    return next;
}

/**
 * Whether a cached entry may be served without going back to the server.
 */
export function isFresh(cached: CachedSearch, now: number): boolean {
    return now - cached.fetchedAt <= SEARCH_CACHE_TTL_MS;
}

/**
 * The results of the longest already-searched prefix of `query`, so a dropdown keeps showing the
 * closest relevant rows while a longer query resolves instead of blanking or holding unrelated ones.
 */
export function nearestPrefixResults(
    cache: SearchCache,
    query: string,
    minQueryLength: number,
): SearchResults | null {
    for (let end = query.length; end >= minQueryLength; end -= 1) {
        const cached = cache.get(query.slice(0, end).trim());
        if (cached) return cached.data;
    }
    return null;
}
