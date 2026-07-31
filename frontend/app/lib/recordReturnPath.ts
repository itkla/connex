export type RecordCollection = 'contacts' | 'companies' | 'deals';

const RETURN_PATHS: Record<RecordCollection, string> = {
    contacts: '/records/contacts',
    companies: '/records/companies',
    deals: '/records/deals',
};

const MAX_RETURN_PATH_LENGTH = 2048;
const VALIDATION_ORIGIN = 'https://connex.invalid';
const RETURN_CONTEXT_KEY = 'connex:record-return-context';
const RETURN_CONTEXT_MAX_AGE_MS = 30 * 60 * 1000;

type RecordReturnContext = {
    detailPath: string;
    returnTo: string;
    createdAt: number;
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
): string {
    const returnTo = `${window.location.pathname}${window.location.search}`;
    const href = recordDetailPath(collection, id, returnTo);
    const context: RecordReturnContext = {
        detailPath: new URL(href, window.location.origin).pathname,
        returnTo,
        createdAt: Date.now(),
    };
    try {
        window.sessionStorage.setItem(RETURN_CONTEXT_KEY, JSON.stringify(context));
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
