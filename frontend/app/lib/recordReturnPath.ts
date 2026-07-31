export type RecordCollection = 'contacts' | 'companies' | 'deals';

const RETURN_PATHS: Record<RecordCollection, string> = {
    contacts: '/records/contacts',
    companies: '/records/companies',
    deals: '/records/deals',
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
        && 'createdAt' in value
        && typeof value.createdAt === 'number';
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
        && (value.collection === 'contacts' || value.collection === 'companies' || value.collection === 'deals')
        && 'returnTo' in value
        && typeof value.returnTo === 'string'
        && 'userId' in value
        && isPositiveSafeInteger(value.userId)
        && 'workspaceId' in value
        && isPositiveSafeInteger(value.workspaceId)
        && 'selectedIds' in value
        && Array.isArray(value.selectedIds)
        && value.selectedIds.length > 0
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
        || selection.ids.length === 0
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
    collection: RecordCollection,
    id: number,
    returnTo?: string,
): string {
    if (!Number.isInteger(id) || id < 1) {
        throw new RangeError('Record id must be a positive integer');
    }
    const path = `${RETURN_PATHS[collection]}/${id}`;
    if (!returnTo) return path;
    return `${path}?${new URLSearchParams({ returnTo }).toString()}`;
}

/** Builds a detail URL and records enough history context for an exact browser-backed return. */
export function recordDetailNavigationPath(
    collection: RecordCollection,
    id: number,
    selection?: RecordReturnSelectionSnapshot,
): string {
    const returnTo = `${window.location.pathname}${window.location.search}`;
    const href = recordDetailPath(collection, id, returnTo);
    const createdAt = Date.now();
    const context: RecordReturnContext = {
        detailPath: new URL(href, window.location.origin).pathname,
        returnTo,
        createdAt,
    };
    try {
        window.sessionStorage.setItem(RETURN_CONTEXT_KEY, JSON.stringify(context));
        const selectedIds = normalizeSelection(selection);
        if (selectedIds && selection) {
            const navigationId = window.crypto.randomUUID();
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
        const matches = isRecordReturnContext(context)
            && context.detailPath === window.location.pathname
            && context.returnTo === returnTo
            && Date.now() - context.createdAt <= RETURN_CONTEXT_MAX_AGE_MS;
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
    const fallback = RETURN_PATHS[collection];
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
