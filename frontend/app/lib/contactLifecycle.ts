import type {
    BuiltInContactDisqualificationReason,
    ContactLifecycleStage,
} from '@/app/lib/types';

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

/** Built-in disqualification reasons with localized labels, in their fallback order. */
export const DISQUALIFICATION_REASONS: readonly BuiltInContactDisqualificationReason[] = [
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

const DISQUALIFICATION_REASON_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,31}$/;

/** Whether a disqualification-reason code has the exact form accepted by the API. */
export function isCanonicalDisqualificationReasonCode(value: string): boolean {
    return DISQUALIFICATION_REASON_CODE_PATTERN.test(value);
}

/** Narrows an open workspace code to one of the built-ins that has an i18n label. */
export function isBuiltInDisqualificationReason(
    value: string,
): value is BuiltInContactDisqualificationReason {
    return DISQUALIFICATION_REASONS.some((reason) => reason === value);
}

/** Resolves stored custom labels, localized built-ins, and unknown historical codes in that order. */
export function disqualificationReasonLabel(
    code: string,
    storedLabel: string | null,
    translate: (key: `reason.${BuiltInContactDisqualificationReason}`) => string,
): string {
    if (storedLabel !== null) return storedLabel;
    return isBuiltInDisqualificationReason(code) ? translate(`reason.${code}`) : code;
}

/** The facet key the server counts contacts that are not in a lead lifecycle under. */
export const LIFECYCLE_NONE_KEY = '__none__';

/** Narrows a facet key or query value to a known stage, rejecting anything unrecognised. */
export function asLifecycleStage(value: string): ContactLifecycleStage | null {
    return LIFECYCLE_STAGES.includes(value as ContactLifecycleStage)
        ? (value as ContactLifecycleStage)
        : null;
}
