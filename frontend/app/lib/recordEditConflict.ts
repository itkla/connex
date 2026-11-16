import { ApiError } from '@/app/lib/api';

/** A stale edit refusal is distinct from other domain-specific conflicts. */
export function isStaleRecordError(error: unknown): error is ApiError {
    return error instanceof ApiError && error.status === 409 && error.code === 'STALE_RECORD';
}

/** Compares a current record with the immutable snapshot that seeded an edit. */
export function changedRecordFields<T, K extends keyof T>(base: T, current: T, fields: readonly K[]): K[] {
    return fields.filter((field) => (base[field] ?? null) !== (current[field] ?? null));
}
