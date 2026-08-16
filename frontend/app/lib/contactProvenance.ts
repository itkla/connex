import type { ContactLeadSource } from '@/app/lib/types';

/**
 * The lead-source vocabulary in display order, mirroring the server's `PersonLeadSource`
 * (issue #559). Used for the browser facet and label lookups.
 */
export const LEAD_SOURCES: readonly ContactLeadSource[] = [
    'REFERRAL',
    'PARTNER',
    'EVENT',
    'WEB',
    'OUTBOUND',
    'BUSINESS_CARD',
    'IMPORT',
    'OTHER',
] as const;

/** The facet key the server counts contacts with uncaptured provenance under. */
export const LEAD_SOURCE_NONE_KEY = '__none__';

/** Sources that may carry a referring contact, mirroring the server rule. */
export const REFERRER_SOURCES: readonly ContactLeadSource[] = ['REFERRAL', 'PARTNER'] as const;

/** Narrows a facet key or query value to a known source, rejecting anything unrecognised. */
export function asLeadSource(value: string): ContactLeadSource | null {
    return LEAD_SOURCES.includes(value as ContactLeadSource)
        ? (value as ContactLeadSource)
        : null;
}
