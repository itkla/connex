import {
    ApiError,
    clientRecoveryScope,
    getBusinessCardImportStatus,
    reserveBusinessCardImport,
} from '@/app/lib/api';
import type { BusinessCardImportResult } from '@/app/lib/types';

const STORAGE_PREFIX = 'connex:business-card-import-recovery:v2:';
const LOCK_PREFIX = 'connex:business-card-import-recovery:';
const REGISTERED_TTL_MS = 24 * 60 * 60 * 1000;
const LEGACY_RECOVERY_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const RECOVERY_POLL_DELAYS_MS = [250, 750, 1500, 3000] as const;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type RecoveryPhase = 'registered' | 'reserved' | 'submitted' | 'legacy';

type RecoveryEntry = {
    requestId: string;
    createdAt: number;
    expiresAt: number;
    phase: RecoveryPhase;
    pendingAvatar: boolean;
    revision: number;
};

export type RecoveredBusinessCardImport = {
    requestId: string;
    result: BusinessCardImportResult | null;
    terminal: 'completed' | 'gone';
    pendingAvatar: boolean;
    revision: number | null;
};

export type BusinessCardImportRecoveryReconciliation = {
    recovered: RecoveredBusinessCardImport | null;
    reusableRequestId: string | null;
    reusableRevision: number | null;
};

/** Indicates that the browser cannot durably retain response-loss recovery state. */
export class BusinessCardRecoveryStorageUnavailableError extends Error {
    constructor() {
        super('Business-card recovery storage is unavailable');
        this.name = 'BusinessCardRecoveryStorageUnavailableError';
    }
}

function parseRecoveryEntry(value: unknown): RecoveryEntry | null {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
    if (!('requestId' in value) || !('createdAt' in value) || !('expiresAt' in value)) return null;
    if (typeof value.requestId !== 'string' || !UUID_PATTERN.test(value.requestId)) return null;
    if (typeof value.createdAt !== 'number' || !Number.isFinite(value.createdAt)) return null;
    if (typeof value.expiresAt !== 'number' || !Number.isFinite(value.expiresAt)) return null;
    if (value.expiresAt <= value.createdAt) return null;
    const storedPhase = 'phase' in value ? value.phase : undefined;
    const phase: RecoveryPhase = storedPhase === 'registered'
        || storedPhase === 'reserved'
        || storedPhase === 'submitted'
        ? storedPhase
        : 'legacy';
    const pendingAvatar = 'pendingAvatar' in value && value.pendingAvatar === true;
    const revision = 'revision' in value
        && typeof value.revision === 'number'
        && Number.isSafeInteger(value.revision)
        && value.revision >= 0
        ? value.revision
        : 0;
    const expiresAt = phase === 'legacy'
        ? Math.max(value.expiresAt, value.createdAt + LEGACY_RECOVERY_TTL_MS)
        : value.expiresAt;
    return {
        requestId: value.requestId,
        createdAt: value.createdAt,
        expiresAt,
        phase,
        pendingAvatar,
        revision,
    };
}

function entryPrefix(scope: string): string {
    return `${STORAGE_PREFIX}${scope}:`;
}

function entryKey(scope: string, requestId: string): string {
    return entryPrefix(scope) + requestId;
}

function removeEntry(scope: string, requestId: string): void {
    try {
        const key = entryKey(scope, requestId);
        window.localStorage.removeItem(key);
        if (window.localStorage.getItem(key) !== null) {
            throw new BusinessCardRecoveryStorageUnavailableError();
        }
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

function readEntries(scope: string): RecoveryEntry[] {
    try {
        const prefix = entryPrefix(scope);
        const entries: RecoveryEntry[] = [];
        const removableKeys: string[] = [];
        const now = Date.now();
        for (let index = 0; index < window.localStorage.length; index += 1) {
            const key = window.localStorage.key(index);
            if (!key?.startsWith(prefix)) continue;
            try {
                const parsed: unknown = JSON.parse(window.localStorage.getItem(key) ?? 'null');
                const entry = parseRecoveryEntry(parsed);
                if (!entry || key !== entryKey(scope, entry.requestId)) {
                    removableKeys.push(key);
                } else if (
                    (entry.phase === 'registered' || entry.phase === 'reserved')
                    && entry.expiresAt <= now
                ) {
                    removableKeys.push(key);
                } else {
                    entries.push(entry);
                }
            } catch {
                removableKeys.push(key);
            }
        }
        for (const key of removableKeys) window.localStorage.removeItem(key);
        return entries.sort((first, second) => first.createdAt - second.createdAt);
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

function readEntry(scope: string, requestId: string): RecoveryEntry | null {
    try {
        const stored = window.localStorage.getItem(entryKey(scope, requestId));
        if (stored === null) return null;
        const entry = parseRecoveryEntry(JSON.parse(stored));
        if (!entry || entry.requestId !== requestId) {
            throw new BusinessCardRecoveryStorageUnavailableError();
        }
        return entry;
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

function persistEntry(scope: string, entry: RecoveryEntry): void {
    try {
        const key = entryKey(scope, entry.requestId);
        window.localStorage.setItem(key, JSON.stringify(entry));
        const persisted = parseRecoveryEntry(JSON.parse(window.localStorage.getItem(key) ?? 'null'));
        if (!persisted
            || persisted.requestId !== entry.requestId
            || persisted.createdAt !== entry.createdAt
            || persisted.expiresAt !== entry.expiresAt
            || persisted.phase !== entry.phase
            || persisted.pendingAvatar !== entry.pendingAvatar
            || persisted.revision !== entry.revision) {
            throw new BusinessCardRecoveryStorageUnavailableError();
        }
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

async function recoveryScope(signal?: AbortSignal): Promise<string> {
    if (typeof window === 'undefined') throw new BusinessCardRecoveryStorageUnavailableError();
    const scope = await clientRecoveryScope({ signal });
    if (!scope) throw new BusinessCardRecoveryStorageUnavailableError();
    return scope;
}

function abortError(signal: AbortSignal): unknown {
    return signal.reason ?? new DOMException('The operation was aborted', 'AbortError');
}

function delay(milliseconds: number, signal?: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
        if (signal?.aborted) {
            reject(abortError(signal));
            return;
        }
        const timer = window.setTimeout(() => {
            signal?.removeEventListener('abort', handleAbort);
            resolve();
        }, milliseconds);
        const handleAbort = () => {
            window.clearTimeout(timer);
            reject(signal ? abortError(signal) : new DOMException('The operation was aborted', 'AbortError'));
        };
        signal?.addEventListener('abort', handleAbort, { once: true });
    });
}

async function withRecoveryLock<T>(
    scope: string,
    operation: () => T,
    signal?: AbortSignal,
): Promise<T> {
    if (!navigator.locks) throw new BusinessCardRecoveryStorageUnavailableError();
    return navigator.locks.request(LOCK_PREFIX + scope, { signal }, operation);
}

async function currentEntry(
    scope: string,
    candidate: RecoveryEntry,
    signal?: AbortSignal,
): Promise<RecoveryEntry | null> {
    return withRecoveryLock(scope, () => {
        const entry = readEntry(scope, candidate.requestId);
        if (!entry
            || entry.createdAt !== candidate.createdAt
            || entry.phase !== candidate.phase
            || entry.revision !== candidate.revision) return null;
        return entry;
    }, signal);
}

async function removeCurrentEntry(
    scope: string,
    candidate: RecoveryEntry,
    signal?: AbortSignal,
): Promise<boolean> {
    return withRecoveryLock(scope, () => {
        const entry = readEntry(scope, candidate.requestId);
        if (!entry
            || entry.createdAt !== candidate.createdAt
            || entry.phase !== candidate.phase
            || entry.revision !== candidate.revision) return false;
        removeEntry(scope, candidate.requestId);
        return true;
    }, signal);
}

/** Durably registers a fresh opaque key before any reservation request may begin. */
export async function registerBusinessCardImportRecovery(
    pendingAvatar: boolean,
    signal?: AbortSignal,
): Promise<string> {
    const scope = await recoveryScope(signal);
    return withRecoveryLock(scope, () => {
        const requestId = window.crypto.randomUUID();
        const createdAt = Date.now();
        persistEntry(scope, {
            requestId,
            createdAt,
            expiresAt: createdAt + REGISTERED_TTL_MS,
            phase: 'registered',
            pendingAvatar,
            revision: 0,
        });
        return requestId;
    }, signal);
}

/** Reserves a durably registered opaque key before multipart upload may begin. */
export async function prepareBusinessCardImportRecovery(
    requestId: string,
    pendingAvatar: boolean,
    signal?: AbortSignal,
): Promise<number> {
    const scope = await recoveryScope(signal);
    const entry = await withRecoveryLock(scope, () => {
        const stored = readEntry(scope, requestId);
        const current = stored ?? {
            requestId,
            createdAt: Date.now(),
            expiresAt: Date.now() + REGISTERED_TTL_MS,
            phase: 'registered' as const,
            pendingAvatar,
            revision: 0,
        };
        if (!stored) persistEntry(scope, current);
        if (current.expiresAt <= Date.now()) {
            removeEntry(scope, requestId);
            throw new ApiError(
                'Business-card import recovery expired',
                410,
                'BUSINESS_CARD_IMPORT_RESULT_GONE',
            );
        }
        return current;
    }, signal);

    const reservation = await reserveBusinessCardImport(requestId, { signal });
    const expiresAt = Date.parse(reservation.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
        throw new Error('Business-card import reservation is unavailable');
    }

    return withRecoveryLock(scope, () => {
        const current = readEntry(scope, requestId);
        if (!current
            || current.createdAt !== entry.createdAt
            || current.phase !== entry.phase
            || current.revision !== entry.revision) {
            throw new ApiError(
                'Business-card import recovery is unavailable',
                410,
                'BUSINESS_CARD_IMPORT_RESULT_GONE',
            );
        }
        const revision = current.revision + 1;
        persistEntry(scope, {
            ...current,
            expiresAt,
            phase: 'submitted',
            pendingAvatar,
            revision,
        });
        return revision;
    }, signal);
}

async function checkInMemoryRequest(
    requestId: string,
    signal?: AbortSignal,
): Promise<BusinessCardImportRecoveryReconciliation> {
    try {
        const result = await getBusinessCardImportStatus(requestId, { signal });
        return {
            recovered: {
                requestId,
                result,
                terminal: 'completed',
                pendingAvatar: false,
                revision: null,
            },
            reusableRequestId: null,
            reusableRevision: null,
        };
    } catch (error) {
        if (!(error instanceof ApiError)) throw error;
        if (error.status === 410) {
            return {
                recovered: {
                    requestId,
                    result: null,
                    terminal: 'gone',
                    pendingAvatar: false,
                    revision: null,
                },
                reusableRequestId: null,
                reusableRevision: null,
            };
        }
        if (error.status === 404 || error.status === 409) {
            return {
                recovered: null,
                reusableRequestId: requestId,
                reusableRevision: null,
            };
        }
        throw error;
    }
}

/** Records that the optional avatar upload completed before import recovery is cleared. */
export async function markBusinessCardImportAvatarCompleted(
    requestId: string,
    signal?: AbortSignal,
): Promise<number | null> {
    const scope = await recoveryScope(signal);
    return withRecoveryLock(scope, () => {
        const entry = readEntry(scope, requestId);
        if (!entry) return null;
        if (!entry.pendingAvatar) return entry.revision;
        const revision = entry.revision + 1;
        persistEntry(scope, {
            ...entry,
            pendingAvatar: false,
            revision,
        });
        return revision;
    }, signal);
}

/** Removes a completed or conclusively rejected import UUID. */
export async function clearBusinessCardImportRecovery(
    requestId: string,
    signal?: AbortSignal,
    expectedRevision?: number | null,
): Promise<void> {
    const scope = await recoveryScope(signal);
    await withRecoveryLock(scope, () => {
        const entry = readEntry(scope, requestId);
        if (!entry) return;
        if (expectedRevision != null && entry.revision !== expectedRevision) {
            throw new ApiError(
                'Business-card recovery changed before acknowledgment',
                409,
                'BUSINESS_CARD_RECOVERY_CHANGED',
            );
        }
        removeEntry(scope, requestId);
    }, signal);
}

type EntryCheckOutcome = {
    recovered: RecoveredBusinessCardImport | null;
    reusableRequestId: string | null;
    reusableRevision: number | null;
    pending: boolean;
};

async function checkEntries(
    scope: string,
    entries: RecoveryEntry[],
    signal?: AbortSignal,
): Promise<EntryCheckOutcome> {
    let pending = false;
    let reusableRequestId: string | null = null;
    let reusableRevision: number | null = null;
    for (const entry of entries) {
        try {
            const result = await getBusinessCardImportStatus(entry.requestId, { signal });
            const current = await currentEntry(scope, entry, signal);
            if (!current) continue;
            return {
                recovered: {
                    requestId: entry.requestId,
                    result,
                    terminal: 'completed',
                    pendingAvatar: current.pendingAvatar,
                    revision: current.revision,
                },
                reusableRequestId: null,
                reusableRevision: null,
                pending,
            };
        } catch (error) {
            if (!(error instanceof ApiError)) throw error;
            if (error.status === 410) {
                const current = await currentEntry(scope, entry, signal);
                if (!current) continue;
                return {
                    recovered: {
                        requestId: entry.requestId,
                        result: null,
                        terminal: 'gone',
                        pendingAvatar: false,
                        revision: current.revision,
                    },
                    reusableRequestId: null,
                    reusableRevision: null,
                    pending,
                };
            }
            if (error.status !== 404 && error.status !== 409) throw error;
            const current = await currentEntry(scope, entry, signal);
            if (!current) continue;
            if (current.phase === 'registered' || current.phase === 'reserved') {
                if (!reusableRequestId) {
                    reusableRequestId = current.requestId;
                    reusableRevision = current.revision;
                }
                continue;
            }
            if (error.status === 409 || Date.now() < current.expiresAt) {
                pending = true;
                continue;
            }
            await removeCurrentEntry(scope, current, signal);
        }
    }
    return { recovered: null, reusableRequestId, reusableRevision, pending };
}

/** Reconciles opaque response-loss candidates without loading private draft data. */
export async function reconcileBusinessCardImportRecovery(
    signal?: AbortSignal,
    inMemoryRequestId?: string | null,
): Promise<BusinessCardImportRecoveryReconciliation> {
    const scope = await recoveryScope(signal);
    for (let attempt = 0; attempt <= RECOVERY_POLL_DELAYS_MS.length; attempt += 1) {
        const entries = await withRecoveryLock(scope, () => readEntries(scope), signal);
        const candidates = inMemoryRequestId
            ? entries.filter((entry) => entry.requestId === inMemoryRequestId)
            : entries.filter((entry) => entry.phase === 'submitted' || entry.phase === 'legacy');
        if (inMemoryRequestId && candidates.length === 0) {
            return checkInMemoryRequest(inMemoryRequestId, signal);
        }
        if (candidates.length === 0) {
            return { recovered: null, reusableRequestId: null, reusableRevision: null };
        }
        const outcome = await checkEntries(scope, candidates, signal);
        if (outcome.recovered) {
            return {
                recovered: outcome.recovered,
                reusableRequestId: null,
                reusableRevision: null,
            };
        }
        if (!outcome.pending) {
            return {
                recovered: null,
                reusableRequestId: outcome.reusableRequestId,
                reusableRevision: outcome.reusableRevision,
            };
        }
        const nextDelay = RECOVERY_POLL_DELAYS_MS[attempt];
        if (nextDelay == null) {
            throw new Error('Business-card recovery is still pending');
        }
        await delay(nextDelay, signal);
    }
    return { recovered: null, reusableRequestId: null, reusableRevision: null };
}
