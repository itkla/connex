import type { Contact, ContactFirstResponseState } from '@/app/lib/types';

/**
 * The first-response SLA vocabulary in display order, mirroring the server's
 * `PersonFirstResponseState` (issue #559). Used for the browser facet and label lookups.
 */
export const FIRST_RESPONSE_STATES: readonly ContactFirstResponseState[] = [
    'OVERDUE',
    'PENDING',
    'RESPONDED',
] as const;

/** The facet key the server counts contacts under no first-response SLA with. */
export const FIRST_RESPONSE_NONE_KEY = '__none__';

/** Narrows a facet key or query value to a known state, rejecting anything unrecognised. */
export function asFirstResponseState(value: string): ContactFirstResponseState | null {
    return FIRST_RESPONSE_STATES.includes(value as ContactFirstResponseState)
        ? (value as ContactFirstResponseState)
        : null;
}

/**
 * The single SLA state a contact's three timestamps describe, or `null` when it was never put
 * under an SLA. A contact answered after its deadline reads as `RESPONDED`: the breach stays on
 * the record as evidence, but it is no longer waiting on anyone. This mirrors the server's own
 * collapse of the three columns, so the record view and the browser facet agree.
 */
export function firstResponseStateOf(contact: Contact): ContactFirstResponseState | null {
    if (!contact.firstResponseDueAt) return null;
    if (contact.firstRespondedAt) return 'RESPONDED';
    return contact.firstResponseBreachedAt ? 'OVERDUE' : 'PENDING';
}
