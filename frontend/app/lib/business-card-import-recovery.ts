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
};

export type RecoveredBusinessCardImport = {
    requestId: string;
    result: BusinessCardImportResult | null;
    terminal: 'completed' | 'gone';
    pendingAvatar: boolean;
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
    const expiresAt = phase === 'legacy'
        ? Math.max(value.expiresAt, value.createdAt + LEGACY_RECOVERY_TTL_MS)
        : value.expiresAt;
    return {
        requestId: value.requestId,
        createdAt: value.createdAt,
        expiresAt,
        phase,
        pendingAvatar,
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

function readEntry(scope: string, requestId: string): RecoveryEntry {
    try {
        const parsed: unknown = JSON.parse(
            window.localStorage.getItem(entryKey(scope, requestId)) ?? 'null',
        );
        const entry = parseRecoveryEntry(parsed);
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
            || persisted.pendingAvatar !== entry.pendingAvatar) {
            throw new BusinessCardRecoveryStorageUnavailableError();
        }
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

async function recoveryScope(): Promise<string> {
    if (typeof window === 'undefined') throw new BusinessCardRecoveryStorageUnavailableError();
    try {
        const scope = await clientRecoveryScope();
        if (!scope) throw new BusinessCardRecoveryStorageUnavailableError();
        return scope;
    } catch (error) {
        if (error instanceof BusinessCardRecoveryStorageUnavailableError) throw error;
        throw new BusinessCardRecoveryStorageUnavailableError();
    }
}

function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

async function withRecoveryLock<T>(scope: string, operation: () => Promise<T> | T): Promise<T> {
    if (!navigator.locks) throw new BusinessCardRecoveryStorageUnavailableError();
    return navigator.locks.request(LOCK_PREFIX + scope, operation);
}

/** Durably registers, reserves, and submits one opaque key before multipart upload may begin. */
export async function prepareBusinessCardImportRecovery(
    requestId: string | null,
    pendingAvatar: boolean,
): Promise<string> {
    const scope = await recoveryScope();
    return withRecoveryLock(scope, async () => {
        const activeRequestId = requestId ?? window.crypto.randomUUID();
        let entry: RecoveryEntry;
        if (requestId) {
            entry = readEntry(scope, activeRequestId);
        } else {
            const createdAt = Date.now();
            entry = {
                requestId: activeRequestId,
                createdAt,
                expiresAt: createdAt + REGISTERED_TTL_MS,
                phase: 'registered',
                pendingAvatar,
            };
            persistEntry(scope, entry);
        }
        if (entry.expiresAt <= Date.now()) {
            removeEntry(scope, activeRequestId);
            throw new ApiError(
                'Business-card import recovery expired',
                410,
                'BUSINESS_CARD_IMPORT_RESULT_GONE',
            );
        }
        const reservation = await reserveBusinessCardImport(activeRequestId);
        const expiresAt = Date.parse(reservation.expiresAt);
        if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
            throw new Error('Business-card import reservation is unavailable');
        }
        persistEntry(scope, {
            ...entry,
            expiresAt,
            phase: entry.phase === 'submitted' || entry.phase === 'legacy'
                ? 'submitted'
                : 'reserved',
        });
        persistEntry(scope, { ...entry, expiresAt, phase: 'submitted', pendingAvatar });
        return activeRequestId;
    });
}

/** Removes a completed or conclusively rejected import UUID. */
export async function clearBusinessCardImportRecovery(requestId: string): Promise<void> {
    const scope = await recoveryScope();
    await withRecoveryLock(scope, () => removeEntry(scope, requestId));
}

async function checkEntries(
    scope: string,
    entries: RecoveryEntry[],
): Promise<{ recovered: RecoveredBusinessCardImport | null; pending: boolean }> {
    let pending = false;
    for (const entry of entries) {
        if (entry.phase === 'registered' || entry.phase === 'reserved') {
            continue;
        }
        try {
            const result = await getBusinessCardImportStatus(entry.requestId);
            return {
                recovered: {
                    requestId: entry.requestId,
                    result,
                    terminal: 'completed',
                    pendingAvatar: entry.pendingAvatar,
                },
                pending,
            };
        } catch (error) {
            if (!(error instanceof ApiError)) throw error;
            if (error.status === 401 || error.status === 403) continue;
            if (error.status === 410) {
                removeEntry(scope, entry.requestId);
                return {
                    recovered: {
                        requestId: entry.requestId,
                        result: null,
                        terminal: 'gone',
                        pendingAvatar: false,
                    },
                    pending,
                };
            }
            if (error.status === 409) {
                pending = true;
                continue;
            }
            if (error.status !== 404) throw error;
            if (Date.now() < entry.expiresAt) {
                pending = true;
                continue;
            }
            removeEntry(scope, entry.requestId);
        }
    }
    return { recovered: null, pending };
}

/** Reconciles opaque response-loss candidates without loading private draft data. */
export async function reconcileBusinessCardImportRecovery(): Promise<RecoveredBusinessCardImport | null> {
    const scope = await recoveryScope();
    return withRecoveryLock(scope, async () => {
        const entries = readEntries(scope);
        for (let attempt = 0; attempt <= RECOVERY_POLL_DELAYS_MS.length; attempt += 1) {
            const outcome = await checkEntries(scope, entries);
            if (outcome.recovered) return outcome.recovered;
            if (!outcome.pending) return null;
            const nextDelay = RECOVERY_POLL_DELAYS_MS[attempt];
            if (nextDelay == null) {
                throw new Error('Business-card recovery is still pending');
            }
            await delay(nextDelay);
        }
        return null;
    });
}
