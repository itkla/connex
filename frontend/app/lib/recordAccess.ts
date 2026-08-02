import { redirect } from 'next/navigation';

import { ApiError } from '@/app/lib/api';

/**
 * Outcome of loading a single record by id, keeping "there is no such record" and
 * "you may not see this record" apart. Collapsing the two — which `.catch(() => null)`
 * followed by `notFound()` does — tells a user that a record they can name does not
 * exist, and hides the fact that the fix is a permission change.
 */
export type RecordAccess<T> =
    | { kind: 'found'; record: T }
    | { kind: 'missing' }
    | { kind: 'forbidden' };

/**
 * Loads one record and classifies the failure.
 *
 * An unauthenticated caller — a 401, or a bodyless 403, which is how Spring Security
 * answers a missing or expired session on a deployment without the OAuth entry point —
 * is redirected to sign in rather than shown a denial. This matters because in-memory
 * sessions are dropped on every backend restart, and routing that to "you don't have
 * access — ask an admin" would strand the user on an unrecoverable dead-end.
 *
 * A 403 with an explanatory body is a genuine authorization denial and reports
 * `forbidden`; a 404 or a `null`/`undefined` result reports `missing`; and **every
 * other failure is rethrown** so a genuine server fault still reaches the segment
 * `error.tsx` instead of masquerading as a tidy 404.
 * @param load fetches the record, resolving to `null` when the API models absence that way
 * @returns the record, or the reason it could not be shown
 */
export async function loadRecord<T>(
    load: () => Promise<T | null | undefined>,
): Promise<RecordAccess<T>> {
    try {
        const record = await load();
        return record == null ? { kind: 'missing' } : { kind: 'found', record };
    } catch (error) {
        if (deniedByPermission(error)) {
            return { kind: 'forbidden' };
        }
        if (error instanceof ApiError && error.status === 404) {
            return { kind: 'missing' };
        }
        throw error;
    }
}

/**
 * Outcome of loading a collection, keeping "this workspace holds no such records" and
 * "you may not see these records" apart. Collapsing the two — which `.catch(() => [])`
 * does — makes a positive claim about the tenant's history that is false, and hides
 * the fact that the fix is a permission change. On an audit surface that claim is the
 * worst of both: an empty security log reads as "nothing happened".
 */
export type CollectionAccess<T> =
    | { kind: 'loaded'; items: T[] }
    | { kind: 'forbidden' };

/**
 * Loads a collection and classifies a permission refusal, so the caller can render the
 * denial grammar rather than an empty state it has no grounds to claim. A collection
 * that genuinely came back empty still reports `loaded`, and so still reaches the
 * honest empty state.
 *
 * Shares {@link loadRecord}'s treatment of an unauthenticated caller, and rethrows
 * every other failure — including a 404, which for a collection endpoint is a fault
 * rather than an empty workspace — so it reaches the segment `error.tsx`.
 * @param load fetches the collection
 * @returns the items, or the fact that the caller may not see them
 */
export async function loadCollection<T>(load: () => Promise<T[]>): Promise<CollectionAccess<T>> {
    try {
        return { kind: 'loaded', items: await load() };
    } catch (error) {
        if (deniedByPermission(error)) {
            return { kind: 'forbidden' };
        }
        throw error;
    }
}

/**
 * Whether a failure is a genuine authorization denial, redirecting an unauthenticated
 * caller to sign in on the way past.
 *
 * A 401, or a bodyless 403 — which is how Spring Security answers a missing or expired
 * session on a deployment without the OAuth entry point — is redirected rather than
 * shown a denial. This matters because in-memory sessions are dropped on every backend
 * restart, and routing that to "you don't have access — ask an admin" would strand the
 * user on an unrecoverable dead-end.
 */
function deniedByPermission(error: unknown): boolean {
    if (!(error instanceof ApiError)) {
        return false;
    }
    if (error.status === 401 || (error.status === 403 && error.emptyBody === true)) {
        redirect('/auth/login');
    }
    return error.status === 403;
}
