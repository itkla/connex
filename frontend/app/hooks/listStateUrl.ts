/** Sort direction for a server-list browser column. */
export type SortDir = 'asc' | 'desc';

/** URL query keys owned by a server-list browser's list state (query, sort, and pagination). Kept in
 * one place so {@link useRecordsBrowser} preserves them rather than wiping them as stale filter params. */
export const SERVER_RECORDS_URL_KEYS = ['q', 'sort', 'dir', 'page', 'size'] as const;

/** Upper bound applied to a URL-supplied page size so a crafted `?size=` can't request an unbounded page. */
export const MAX_URL_PAGE_SIZE = 100;

/**
 * Parses a positive-integer URL param, falling back and clamping to `max` so malformed or hostile
 * values (`?page=-1`, `?size=99999`) never reach the fetcher.
 */
export function parseListInt(value: string | null, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
    if (value === null) return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, max) : fallback;
}

/** The list state a server-list browser reflects into the URL. Omit `q` to leave the `q` param
 * untouched (for browsers whose query lives in another writer). */
export type ListUrlState = {
    q?: string;
    sort: string | null;
    dir: SortDir;
    page: number;
    size: number;
};

/**
 * Reflects a browser's list state into the URL via shallow `history.replaceState` — the #405
 * records-browser contract: it reads live `window.location.search` as its base and only ever
 * set/deletes its own {@link SERVER_RECORDS_URL_KEYS}, so `view`/facet-filter/`peek` params owned by
 * other writers are preserved. Default/empty values are dropped, and it no-ops when the query string
 * is already correct (so it never adds a history entry or loops against another writer).
 *
 * @param pathname - the current path, used to rebuild the URL without a full navigation
 * @param state - the current list state
 * @param defaultSize - the page size that is considered the default (and so omitted from the URL)
 */
export function writeListStateToUrl(pathname: string, state: ListUrlState, defaultSize: number): void {
    const params = new URLSearchParams(window.location.search);
    const set = (key: string, value: string) => (value ? params.set(key, value) : params.delete(key));
    if (state.q !== undefined) set('q', state.q);
    set('sort', state.sort ?? '');
    set('dir', state.sort && state.dir === 'desc' ? 'desc' : '');
    set('page', state.page > 1 ? String(state.page) : '');
    set('size', state.size !== defaultSize ? String(state.size) : '');
    const next = params.toString();
    if (next === window.location.search.replace(/^\?/, '')) return;
    window.history.replaceState(null, '', next ? `${pathname}?${next}` : pathname);
}
