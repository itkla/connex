/**
 * Canonical long-form record-detail section order from #843.
 * Domain adapters choose which slots to fill; they must not invent a competing hierarchy.
 */
export const RECORD_DETAIL_SECTION_ORDER = [
    'identity',
    'actions',
    'notifications',
    'profile',
    'metrics',
    'activity',
    'relationship',
    'related',
    'files',
    'history',
] as const;

/** Section slot identifiers shared across Contact, Company, and Deal detail adapters. */
export type RecordDetailSectionId = (typeof RECORD_DETAIL_SECTION_ORDER)[number];

/**
 * Builds a stable DOM id for a record-detail section so jump links and tests can target grammar slots.
 */
export function recordDetailSectionId(
    recordKind: 'contact' | 'company' | 'deal',
    section: RecordDetailSectionId,
): string {
    return `${recordKind}-detail-${section}`;
}
