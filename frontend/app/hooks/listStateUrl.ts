/** Sort direction for a server-list browser column. */
export type SortDir = 'asc' | 'desc';

/** URL query keys owned by a server-list browser's list state (query, sort, and pagination). Kept in
 * one place so {@link useRecordsBrowser} preserves them rather than wiping them as stale filter params. */
export const SERVER_RECORDS_URL_KEYS = ['q', 'sort', 'dir', 'page', 'size'] as const;

/** Upper bound applied to a URL-supplied page size so a crafted `?size=` can't request an unbounded page. */
export const MAX_URL_PAGE_SIZE = 100;

/** URL query key that points at the active/shared saved view as `<workspaceId>:<id>`. Owned by the
 * SavedViewsBar — a third list-state writer alongside the query/sort and facet-filter writers. */
export const SAVED_VIEW_URL_KEY = 'sv';

/** URL query key that deep-links one task, read by the tasks browser and the record timelines. */
export const TASK_URL_KEY = 'task';

/** URL query key that deep-links one note, read by the notes browser and the record timelines. */
export const NOTE_URL_KEY = 'note';

/** URL query key that deep-links one activity, read by the activities browser and the record timelines. */
export const ACTIVITY_URL_KEY = 'activity';

/** URL query key that deep-links one record comment, read by the record comments section. */
export const COMMENT_URL_KEY = 'comment';

/**
 * The canonical record deep-link query keys, keyed by the record kind each one addresses. Every
 * producer — a backend notification `actionUrl`, a calendar event href, an in-app link — emits these
 * exact keys, because they are what the consuming browser or record surface reads. Mirrored by the
 * shared route fixture in `backend/src/test/resources/frontend-route-manifest.json` so both planes
 * fail loudly when a producer and its consumer drift apart (#1338).
 */
export const DEEP_LINK_URL_KEYS = {
    task: TASK_URL_KEY,
    note: NOTE_URL_KEY,
    activity: ACTIVITY_URL_KEY,
    comment: COMMENT_URL_KEY,
} as const;

/** Normalizes a URL-supplied list query so empty and whitespace-only values share one canonical form. */
export function parseListQuery(value: string | null): string {
    return value?.trim() ?? '';
}

/**
 * Reflects the active saved-view pointer into the URL via shallow `history.replaceState`, following the
 * same #405 records-browser contract as {@link writeListStateToUrl}: it reads live
 * `window.location.search` and only ever set/deletes its own {@link SAVED_VIEW_URL_KEY}, so the
 * query/sort/pagination and facet-filter params owned by the other writers survive untouched. Passing
 * `null` clears the pointer (the "All" view or a config that matches no saved view).
 *
 * @param pathname - the current path, used to rebuild the URL without a full navigation
 * @param sv - the `<workspaceId>:<id>` pointer, or null to remove it
 */
export function writeSavedViewToUrl(pathname: string, sv: string | null): void {
    const params = new URLSearchParams(window.location.search);
    if (sv) params.set(SAVED_VIEW_URL_KEY, sv);
    else params.delete(SAVED_VIEW_URL_KEY);
    const next = params.toString();
    if (next === window.location.search.replace(/^\?/, '')) return;
    window.history.replaceState(null, '', next ? `${pathname}?${next}` : pathname);
}

/**
 * Reflects an arbitrary set of owned params into the URL via shallow `history.replaceState`, following
 * the same #405 records-browser contract as {@link writeListStateToUrl}: it reads live
 * `window.location.search` as its base and only ever set/deletes the keys present in `owned`, so every
 * other param — whether owned by another writer on the page or carried in from a shared link — survives
 * untouched. An `undefined` or empty value deletes its key, which is how a closed deep-linked record
 * clears itself without disturbing the surrounding query, sort, and filter state.
 *
 * The existing `history.state` is carried through rather than overwritten with null, so the router's own
 * bookkeeping and the record-return marker stamped on a list entry both survive a URL write.
 *
 * @param pathname - the current path, used to rebuild the URL without a full navigation
 * @param owned - the complete set of keys this writer owns, mapped to their current values
 */
export function writeOwnedParamsToUrl(
    pathname: string,
    owned: Readonly<Record<string, string | undefined>>,
): void {
    const params = new URLSearchParams(window.location.search);
    for (const [key, value] of Object.entries(owned)) {
        if (value) params.set(key, value);
        else params.delete(key);
    }
    const next = params.toString();
    if (next === window.location.search.replace(/^\?/, '')) return;
    window.history.replaceState(window.history.state, '', next ? `${pathname}?${next}` : pathname);
}

/** Reflects only the query owner into the URL while preserving sort, pagination, filters, and deep links. */
export function writeListQueryToUrl(pathname: string, query: string): void {
    const params = new URLSearchParams(window.location.search);
    if (query) params.set('q', query);
    else params.delete('q');
    const next = params.toString();
    if (next === window.location.search.replace(/^\?/, '')) return;
    window.history.replaceState(null, '', next ? `${pathname}?${next}` : pathname);
}

/**
 * Prepares a cross-workspace saved-view reload by retaining only the target saved-view pointer. All
 * other record-browser URL state belongs to the old workspace and must be removed synchronously before
 * the reload can restore state in the target workspace.
 *
 * @param pathname - the record-browser path that will reload in the target workspace
 * @param sv - the target workspace's saved-view pointer
 */
export function writeWorkspaceSavedViewToUrl(pathname: string, sv: string): void {
    const params = new URLSearchParams({ [SAVED_VIEW_URL_KEY]: sv });
    const next = params.toString();
    if (next === window.location.search.replace(/^\?/, '')) return;
    window.history.replaceState(null, '', `${pathname}?${next}`);
}

/**
 * Parses a positive-integer URL param, falling back and clamping to `max` so malformed or hostile
 * values (`?page=-1`, `?size=99999`) never reach the fetcher.
 */
export function parseListInt(value: string | null, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
    if (value === null) return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, max) : fallback;
}

/**
 * Parses a deep-linked record id param, rejecting anything that is not a plain positive integer so a
 * crafted `?task=0` or `?file=1e3` never reaches a record fetcher.
 */
export function parseDeepLinkId(value: string | null): number | null {
    if (value === null || !/^\d+$/.test(value)) return null;
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
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
