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
 * A 403 reports `forbidden`, a 404 or a `null`/`undefined` result reports `missing`,
 * and **every other failure is rethrown** so a genuine server fault still reaches the
 * segment `error.tsx` instead of masquerading as a tidy 404. That rethrow is the point
 * of the helper: silently turning a 500 into "not found" is the failure mode this
 * replaces.
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
        if (error instanceof ApiError) {
            if (error.status === 403) {
                return { kind: 'forbidden' };
            }
            if (error.status === 404) {
                return { kind: 'missing' };
            }
        }
        throw error;
    }
}
