export type RecordCollection =
    | 'contacts'
    | 'companies'
    | 'deals'
    | 'tasks'
    | 'activities'
    | 'notes'
    | 'files';

/**
 * The collections that have a record-detail route. Files are browsed and opened entirely in-page, so
 * they can carry return context for their own list but can never be a detail-navigation target — the
 * exclusion makes that a compile error rather than a fabricated URL.
 */
export type DetailRecordCollection = Exclude<RecordCollection, 'files'>;

/** Where each collection's list lives. This is the allowlist a return target is validated against. */
const LIST_PATHS: Record<RecordCollection, string> = {
    contacts: '/records/contacts',
    companies: '/records/companies',
    deals: '/records/deals',
    tasks: '/activity/tasks',
    activities: '/activity/all',
    notes: '/activity/notes',
    files: '/library/files',
};

/** Where each collection's detail route lives. Kept separate from {@link LIST_PATHS} because a detail
 * route is not always the list path plus an id — activities are listed under `/activity/all` but
 * detailed under `/activity/activities/{id}`. */
const DETAIL_PATHS: Record<DetailRecordCollection, string> = {
    contacts: '/records/contacts',
    companies: '/records/companies',
    deals: '/records/deals',
    tasks: '/activity/tasks',
    activities: '/activity/activities',
    notes: '/activity/notes',
};

const MAX_RETURN_PATH_LENGTH = 2048;
const VALIDATION_ORIGIN = 'https://connex.invalid';
const RETURN_CONTEXT_KEY = 'connex:record-return-context';
const RETURN_SELECTION_KEY = 'connex:record-return-selection';
const RETURN_HISTORY_STATE_KEY = 'connexRecordReturn';
const RETURN_CONTEXT_MAX_AGE_MS = 30 * 60 * 1000;
const MAX_RETURN_SELECTION_SIZE = 1000;

type RecordReturnContext = {
    detailPath: string;
    returnTo: string;
    navigationId: string;
    createdAt: number;
};

export type RecordReturnSelectionSnapshot = {
    userId: number;
    workspaceId: number;
    ids: readonly number[];
};

type RecordReturnSelection = {
    collection: RecordCollection;
    returnTo: string;
    userId: number;
    workspaceId: number;
    selectedIds: number[];
    scrollTop: number;
    navigationId: string;
    createdAt: number;
};

export type RestoredRecordSelection = {
    ids: number[];
    scrollTop: number;
};

function isRecordReturnContext(value: unknown): value is RecordReturnContext {
    return typeof value === 'object'
        && value !== null
        && 'detailPath' in value
        && typeof value.detailPath === 'string'
        && 'returnTo' in value
        && typeof value.returnTo === 'string'
        && 'navigationId' in value
        && typeof value.navigationId === 'string'
        && value.navigationId.length > 0
        && value.navigationId.length <= 128
        && 'createdAt' in value
        && typeof value.createdAt === 'number';
}

function clearHistoryContextAfterTraversal(navigationId: string): void {
    window.addEventListener('popstate', () => {
        try {
            const raw = window.sessionStorage.getItem(RETURN_CONTEXT_KEY);
            if (!raw) return;
            const context: unknown = JSON.parse(raw);
            if (isRecordReturnContext(context) && context.navigationId === navigationId) {
                window.sessionStorage.removeItem(RETURN_CONTEXT_KEY);
            }
        } catch {
            try {
                window.sessionStorage.removeItem(RETURN_CONTEXT_KEY);
            } catch {
                return;
            }
        }
    }, { once: true });
}

function isPositiveSafeInteger(value: unknown): value is number {
    return typeof value === 'number'
        && Number.isSafeInteger(value)
        && value > 0;
}

function isRecordReturnSelection(value: unknown): value is RecordReturnSelection {
    return typeof value === 'object'
        && value !== null
        && 'collection' in value
        && typeof value.collection === 'string'
        && Object.hasOwn(LIST_PATHS, value.collection)
        && 'returnTo' in value
        && typeof value.returnTo === 'string'
        && 'userId' in value
        && isPositiveSafeInteger(value.userId)
        && 'workspaceId' in value
        && isPositiveSafeInteger(value.workspaceId)
        && 'selectedIds' in value
        && Array.isArray(value.selectedIds)
        && value.selectedIds.length <= MAX_RETURN_SELECTION_SIZE
        && value.selectedIds.every(isPositiveSafeInteger)
        && 'scrollTop' in value
        && typeof value.scrollTop === 'number'
        && Number.isFinite(value.scrollTop)
        && value.scrollTop >= 0
        && 'navigationId' in value
        && typeof value.navigationId === 'string'
        && value.navigationId.length > 0
        && value.navigationId.length <= 128
        && 'createdAt' in value
        && typeof value.createdAt === 'number'
        && Number.isFinite(value.createdAt);
}

function recordReturnHistoryState(navigationId: string): object {
    const currentState: unknown = window.history.state;
    return typeof currentState === 'object' && currentState !== null
        ? { ...currentState, [RETURN_HISTORY_STATE_KEY]: navigationId }
        : { [RETURN_HISTORY_STATE_KEY]: navigationId };
}

function matchesRecordReturnHistory(navigationId: string): boolean {
    const currentState: unknown = window.history.state;
    return typeof currentState === 'object'
        && currentState !== null
        && RETURN_HISTORY_STATE_KEY in currentState
        && currentState[RETURN_HISTORY_STATE_KEY] === navigationId;
}

function clearRecordReturnHistoryMarker(): void {
    const currentState: unknown = window.history.state;
    if (
        typeof currentState !== 'object'
        || currentState === null
        || !(RETURN_HISTORY_STATE_KEY in currentState)
    ) {
        return;
    }
    window.history.replaceState(
        { ...currentState, [RETURN_HISTORY_STATE_KEY]: null },
        '',
    );
}

function normalizeSelection(
    selection: RecordReturnSelectionSnapshot | undefined,
): number[] | null {
    if (
        !selection
        || !isPositiveSafeInteger(selection.userId)
        || !isPositiveSafeInteger(selection.workspaceId)
        || selection.ids.length > MAX_RETURN_SELECTION_SIZE
    ) {
        return null;
    }
    const ids = [...new Set(selection.ids)];
    return ids.length <= MAX_RETURN_SELECTION_SIZE && ids.every(isPositiveSafeInteger)
        ? ids
        : null;
}

/** Builds a record-detail URL carrying its exact originating list state. */
export function recordDetailPath(
    collection: DetailRecordCollection,
    id: number,
    returnTo?: string,
): string {
    if (!Number.isInteger(id) || id < 1) {
        throw new RangeError('Record id must be a positive integer');
    }
    const path = `${DETAIL_PATHS[collection]}/${id}`;
    if (!returnTo) return path;
    return `${path}?${new URLSearchParams({ returnTo }).toString()}`;
}

/** Builds a detail URL and records enough history context for an exact browser-backed return. */
export function recordDetailNavigationPath(
    collection: DetailRecordCollection,
    id: number,
    selection?: RecordReturnSelectionSnapshot,
): string {
    const returnTo = `${window.location.pathname}${window.location.search}`;
    const href = recordDetailPath(collection, id, returnTo);
    const createdAt = Date.now();
    const navigationId = window.crypto.randomUUID();
    const context: RecordReturnContext = {
        detailPath: new URL(href, window.location.origin).pathname,
        returnTo,
        navigationId,
        createdAt,
    };
    try {
        window.sessionStorage.setItem(RETURN_CONTEXT_KEY, JSON.stringify(context));
        clearHistoryContextAfterTraversal(navigationId);
        const selectedIds = normalizeSelection(selection);
        if (selectedIds && selection) {
            const scrollTop = document.querySelector<HTMLElement>('[data-app-main]')?.scrollTop ?? 0;
            const returnSelection: RecordReturnSelection = {
                collection,
                returnTo,
                userId: selection.userId,
                workspaceId: selection.workspaceId,
                selectedIds,
                scrollTop,
                navigationId,
                createdAt,
            };
            window.sessionStorage.setItem(RETURN_SELECTION_KEY, JSON.stringify(returnSelection));
            window.history.replaceState(recordReturnHistoryState(navigationId), '');
        } else {
            window.sessionStorage.removeItem(RETURN_SELECTION_KEY);
        }
    } catch {
        return href;
    }
    return href;
}

/** Consumes a matching recent navigation marker when browser Back can restore the live list page. */
export function consumeRecordHistoryReturn(returnTo: string): boolean {
    let raw: string | null;
    try {
        raw = window.sessionStorage.getItem(RETURN_CONTEXT_KEY);
    } catch {
        return false;
    }
    if (!raw) return false;

    try {
        const context: unknown = JSON.parse(raw);
        const age = isRecordReturnContext(context) ? Date.now() - context.createdAt : -1;
        const matches = isRecordReturnContext(context)
            && context.detailPath === window.location.pathname
            && context.returnTo === returnTo
            && age >= 0
            && age <= RETURN_CONTEXT_MAX_AGE_MS;
        if (matches) {
            window.sessionStorage.removeItem(RETURN_CONTEXT_KEY);
            return true;
        }
    } catch {
        window.sessionStorage.removeItem(RETURN_CONTEXT_KEY);
    }
    return false;
}

/** Consumes a recent selection snapshot scoped to the exact restored list and active owner. */
export function consumeRecordReturnSelection(
    collection: RecordCollection,
    userId: number,
    workspaceId: number,
): RestoredRecordSelection | null {
    let raw: string | null;
    try {
        raw = window.sessionStorage.getItem(RETURN_SELECTION_KEY);
        if (raw) window.sessionStorage.removeItem(RETURN_SELECTION_KEY);
    } catch {
        return null;
    }
    if (!raw) return null;

    try {
        const selection: unknown = JSON.parse(raw);
        if (!isRecordReturnSelection(selection)) return null;
        const currentPath = `${window.location.pathname}${window.location.search}`;
        const age = Date.now() - selection.createdAt;
        if (
            selection.collection !== collection
            || selection.userId !== userId
            || selection.workspaceId !== workspaceId
            || selection.returnTo !== currentPath
            || resolveRecordReturnPath(collection, selection.returnTo) !== selection.returnTo
            || !matchesRecordReturnHistory(selection.navigationId)
            || age < 0
            || age > RETURN_CONTEXT_MAX_AGE_MS
        ) {
            return null;
        }
        clearRecordReturnHistoryMarker();
        return {
            ids: [...new Set(selection.selectedIds)],
            scrollTop: selection.scrollTop,
        };
    } catch {
        return null;
    }
}

/** Resolves an untrusted detail-page return target to its allowlisted collection URL. */
export function resolveRecordReturnPath(
    collection: RecordCollection,
    value: string | string[] | undefined,
): string {
    const fallback = LIST_PATHS[collection];
    if (
        typeof value !== 'string'
        || value.length === 0
        || value.length > MAX_RETURN_PATH_LENGTH
        || !value.startsWith('/')
        || value.startsWith('//')
        || value.includes('\\')
        || value.includes('#')
        || /[\u0000-\u001f\u007f]/.test(value)
    ) {
        return fallback;
    }

    try {
        const url = new URL(value, VALIDATION_ORIGIN);
        if (
            url.origin !== VALIDATION_ORIGIN
            || url.pathname !== fallback
            || url.hash
            || url.username
            || url.password
        ) {
            return fallback;
        }
        return `${url.pathname}${url.search}`;
    } catch {
        return fallback;
    }
}
