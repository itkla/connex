import type { ContactDisqualificationReason, ContactLifecycleStage } from '@/app/lib/types';

/**
 * The lead-lifecycle vocabulary in the order the pipeline reads, mirroring the server's
 * `PersonLifecycleStage` (issue #559). Used for filter facets and label lookups; the *permitted*
 * moves from a given stage come from the server on each contact, never from this list.
 */
export const LIFECYCLE_STAGES: readonly ContactLifecycleStage[] = [
    'NEW',
    'WORKING',
    'NURTURING',
    'QUALIFIED',
    'DISQUALIFIED',
    'CONVERTED',
    'RECYCLED',
] as const;

/** The disqualification reasons the server accepts, in the order they are offered. */
export const DISQUALIFICATION_REASONS: readonly ContactDisqualificationReason[] = [
    'NO_BUDGET',
    'NO_FIT',
    'NO_AUTHORITY',
    'BAD_TIMING',
    'COMPETITOR',
    'DUPLICATE',
    'UNRESPONSIVE',
    'SPAM',
    'OTHER',
] as const;

/** The facet key the server counts contacts that are not in a lead lifecycle under. */
export const LIFECYCLE_NONE_KEY = '__none__';

/** Narrows a facet key or query value to a known stage, rejecting anything unrecognised. */
export function asLifecycleStage(value: string): ContactLifecycleStage | null {
    return LIFECYCLE_STAGES.includes(value as ContactLifecycleStage)
        ? (value as ContactLifecycleStage)
        : null;
}
