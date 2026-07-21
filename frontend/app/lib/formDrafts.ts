const DRAFT_PREFIX = 'connex:draft:';

/** Default freshness window: drafts older than this are treated as expired and swept. */
export const DEFAULT_DRAFT_FRESHNESS_MS = 60 * 60 * 1000;

/**
 * Schema version per composer form type. Bump when a form's draft shape changes; a stored draft whose
 * version no longer matches its form type is dropped on read (the version lives in the envelope, not the
 * key, so a newer reader still sees and sweeps older drafts).
 */
export const DRAFT_VERSIONS: Record<string, number> = { activity: 1 };

/** The parts that uniquely scope a draft to a user, workspace, form type, and entity context. */
export type DraftKeyParts = {
    userId: number | null;
    workspaceId: number | null;
    formType: string;
    /** Entity context the composer opened in, e.g. `person:123`, `deal:45`, or `global`. */
    scope: string;
};

type DraftEnvelope<T> = {
    v: number;
    savedAt: number;
    scope: string;
    formType: string;
    data: T;
};

/** A stored draft recovered from the store, carrying enough context to re-route it to its composer. */
export type StoredDraft<T> = {
    key: string;
    scope: string;
    formType: string;
    savedAt: number;
    data: T;
};

/** Prefix that scopes every draft key to one user + workspace, so drafts never leak across either. */
function ownerPrefix(userId: number | null, workspaceId: number | null): string {
    return `${DRAFT_PREFIX}${userId ?? 'anon'}:${workspaceId ?? 'none'}:`;
}

/** Builds the stable sessionStorage key for a draft. User + workspace prevent same-tab re-login bleed. */
export function draftKey({ userId, workspaceId, formType, scope }: DraftKeyParts): string {
    return `${ownerPrefix(userId, workspaceId)}${formType}:${scope}`;
}

function safeSession(): Storage | null {
    if (typeof window === 'undefined') return null;
    try {
        return window.sessionStorage;
    } catch {
        return null;
    }
}

function parseDraftEnvelope(raw: string): DraftEnvelope<unknown> | null {
    let value: unknown;
    try {
        value = JSON.parse(raw);
    } catch {
        return null;
    }
    if (
        typeof value !== 'object' ||
        value === null ||
        !('v' in value) ||
        typeof value.v !== 'number' ||
        !('savedAt' in value) ||
        typeof value.savedAt !== 'number' ||
        !('scope' in value) ||
        typeof value.scope !== 'string' ||
        !('formType' in value) ||
        typeof value.formType !== 'string' ||
        !('data' in value)
    ) {
        return null;
    }
    return {
        v: value.v,
        savedAt: value.savedAt,
        scope: value.scope,
        formType: value.formType,
        data: value.data,
    };
}

/** Persists a draft envelope under `key`. No-op when storage is unavailable (SSR, private mode, quota). */
export function writeDraft<T>(key: string, params: { version: number; scope: string; formType: string; data: T }): void {
    const store = safeSession();
    if (!store) return;
    const envelope: DraftEnvelope<T> = {
        v: params.version,
        savedAt: Date.now(),
        scope: params.scope,
        formType: params.formType,
        data: params.data,
    };
    try {
        store.setItem(key, JSON.stringify(envelope));
    } catch {
        /* quota exceeded or private mode — drafts are best-effort */
    }
}

/** Removes a single draft. */
export function clearDraft(key: string): void {
    const store = safeSession();
    if (!store) return;
    try {
        store.removeItem(key);
    } catch {
        /* ignore */
    }
}

function isFresh(env: DraftEnvelope<unknown>, expectedVersion: number, freshnessMs: number): boolean {
    return (
        typeof env === 'object' &&
        env !== null &&
        env.v === expectedVersion &&
        env.data !== null &&
        env.data !== undefined &&
        typeof env.savedAt === 'number' &&
        Date.now() - env.savedAt <= freshnessMs
    );
}

/** Reads a draft by key, validating version + freshness; drops and returns null if invalid or expired. */
export function readDraft(key: string, opts: { version: number; freshnessMs?: number }): StoredDraft<unknown> | null {
    const store = safeSession();
    if (!store) return null;
    const freshnessMs = opts.freshnessMs ?? DEFAULT_DRAFT_FRESHNESS_MS;
    let raw: string | null = null;
    try {
        raw = store.getItem(key);
    } catch {
        return null;
    }
    if (raw === null) return null;
    const env = parseDraftEnvelope(raw);
    if (!env || !key.endsWith(`:${env.formType}:${env.scope}`) || !isFresh(env, opts.version, freshnessMs)) {
        clearDraft(key);
        return null;
    }
    return { key, scope: env.scope, formType: env.formType, savedAt: env.savedAt, data: env.data };
}

/**
 * Enumerates the fresh, current-version drafts belonging to one user + workspace, sweeping any that are
 * stale or malformed. Scoping to the caller's identity is load-bearing: a draft must never surface for a
 * different user or workspace sharing the tab, or resuming it would create data in the wrong tenant.
 */
export function listFreshDrafts(opts: {
    userId: number | null;
    workspaceId: number | null;
    freshnessMs?: number;
}): StoredDraft<unknown>[] {
    const store = safeSession();
    if (!store) return [];
    const freshnessMs = opts.freshnessMs ?? DEFAULT_DRAFT_FRESHNESS_MS;
    const scopedPrefix = ownerPrefix(opts.userId, opts.workspaceId);
    const out: StoredDraft<unknown>[] = [];
    let keys: string[] = [];
    try {
        keys = Object.keys(store);
    } catch {
        return [];
    }
    for (const key of keys) {
        if (!key.startsWith(scopedPrefix)) continue;
        let raw: string | null = null;
        try {
            raw = store.getItem(key);
        } catch {
            continue;
        }
        const env = raw ? parseDraftEnvelope(raw) : null;
        const expectedVersion = env && typeof env === 'object' ? DRAFT_VERSIONS[env.formType] : undefined;
        const expectedKey = env
            ? draftKey({
                  userId: opts.userId,
                  workspaceId: opts.workspaceId,
                  formType: env.formType,
                  scope: env.scope,
              })
            : null;
        if (!env || expectedKey !== key || expectedVersion === undefined || !isFresh(env, expectedVersion, freshnessMs)) {
            clearDraft(key);
            continue;
        }
        out.push({ key, scope: env.scope, formType: env.formType, savedAt: env.savedAt, data: env.data });
    }
    return out;
}

/** Removes every draft in the store. Call on logout so note/activity drafts never survive a re-login. */
export function clearAllDrafts(): void {
    const store = safeSession();
    if (!store) return;
    let keys: string[] = [];
    try {
        keys = Object.keys(store);
    } catch {
        return;
    }
    for (const key of keys) {
        if (key.startsWith(DRAFT_PREFIX)) clearDraft(key);
    }
}
