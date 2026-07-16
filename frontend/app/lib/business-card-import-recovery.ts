import {
    ApiError,
    clientRecoveryScope,
    getBusinessCardImportStatus,
} from '@/app/lib/api';
import type { BusinessCardImportResult } from '@/app/lib/types';

const STORAGE_PREFIX = 'connex:business-card-import-recovery:v2:';
const RECOVERY_TTL_MS = 23 * 60 * 60 * 1000;
const NOT_FOUND_GRACE_MS = 2 * 60 * 1000;
const RECOVERY_POLL_DELAYS_MS = [250, 750, 1500, 3000] as const;
const MAX_RECOVERIES = 8;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type RecoveryEntry = {
    requestId: string;
    createdAt: number;
    expiresAt: number;
};

export type RecoveredBusinessCardImport = {
    requestId: string;
    result: BusinessCardImportResult;
};

function isRecoveryEntry(value: unknown): value is RecoveryEntry {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
    if (!('requestId' in value) || !('createdAt' in value) || !('expiresAt' in value)) return false;
    return typeof value.requestId === 'string'
        && UUID_PATTERN.test(value.requestId)
        && typeof value.createdAt === 'number'
        && Number.isFinite(value.createdAt)
        && typeof value.expiresAt === 'number'
        && Number.isFinite(value.expiresAt)
        && value.expiresAt > value.createdAt;
}

function entryPrefix(scope: string): string {
    return `${STORAGE_PREFIX}${scope}:`;
}

function entryKey(scope: string, requestId: string): string {
    return entryPrefix(scope) + requestId;
}

function readEntries(scope: string): RecoveryEntry[] {
    const prefix = entryPrefix(scope);
    const entries: RecoveryEntry[] = [];
    const expiredKeys: string[] = [];
    const now = Date.now();
    for (let index = 0; index < window.localStorage.length; index += 1) {
        const key = window.localStorage.key(index);
        if (!key?.startsWith(prefix)) continue;
        try {
            const parsed: unknown = JSON.parse(window.localStorage.getItem(key) ?? 'null');
            if (!isRecoveryEntry(parsed) || key !== entryKey(scope, parsed.requestId)) {
                expiredKeys.push(key);
            } else if (parsed.expiresAt <= now) {
                expiredKeys.push(key);
            } else {
                entries.push(parsed);
            }
        } catch {
            expiredKeys.push(key);
        }
    }
    for (const key of expiredKeys) window.localStorage.removeItem(key);
    return entries.sort((first, second) => first.createdAt - second.createdAt);
}

function persistEntry(scope: string, entry: RecoveryEntry): void {
    const key = entryKey(scope, entry.requestId);
    const encoded = JSON.stringify(entry);
    window.localStorage.setItem(key, encoded);
    const persisted: unknown = JSON.parse(window.localStorage.getItem(key) ?? 'null');
    if (!isRecoveryEntry(persisted)
        || persisted.requestId !== entry.requestId
        || persisted.createdAt !== entry.createdAt
        || persisted.expiresAt !== entry.expiresAt) {
        throw new Error('Business-card recovery storage is unavailable');
    }
}

function pruneEntries(scope: string): void {
    const entries = readEntries(scope);
    for (const entry of entries.slice(0, Math.max(0, entries.length - MAX_RECOVERIES))) {
        window.localStorage.removeItem(entryKey(scope, entry.requestId));
    }
}

async function recoveryScope(): Promise<string | null> {
    if (typeof window === 'undefined') return null;
    return clientRecoveryScope();
}

function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

/** Persists one opaque import UUID before the multipart request begins. */
export async function registerBusinessCardImportRecovery(): Promise<string> {
    const scope = await recoveryScope();
    if (!scope) throw new Error('Business-card recovery storage is unavailable');
    const requestId = window.crypto.randomUUID();
    const createdAt = Date.now();
    persistEntry(scope, {
        requestId,
        createdAt,
        expiresAt: createdAt + RECOVERY_TTL_MS,
    });
    pruneEntries(scope);
    return requestId;
}

/** Removes a completed or conclusively rejected import UUID. */
export async function clearBusinessCardImportRecovery(requestId: string): Promise<void> {
    const scope = await recoveryScope();
    if (!scope) return;
    window.localStorage.removeItem(entryKey(scope, requestId));
}

async function checkEntries(
    scope: string,
    entries: RecoveryEntry[],
): Promise<{ recovered: RecoveredBusinessCardImport | null; pending: boolean }> {
    let pending = false;
    for (const entry of entries) {
        try {
            const result = await getBusinessCardImportStatus(entry.requestId);
            return { recovered: { requestId: entry.requestId, result }, pending };
        } catch (error) {
            if (!(error instanceof ApiError)) throw error;
            if (error.status === 401 || error.status === 403) continue;
            if (error.status === 409) {
                pending = true;
                continue;
            }
            if (error.status !== 404) throw error;
            if (Date.now() - entry.createdAt < NOT_FOUND_GRACE_MS) {
                pending = true;
                continue;
            }
            window.localStorage.removeItem(entryKey(scope, entry.requestId));
        }
    }
    return { recovered: null, pending };
}

/** Reconciles opaque response-loss candidates without loading private draft data. */
export async function reconcileBusinessCardImportRecovery(): Promise<RecoveredBusinessCardImport | null> {
    const scope = await recoveryScope();
    if (!scope) return null;
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
}
