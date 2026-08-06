/**
 * Canonical long-form record-detail section order from #843.
 * Domain adapters choose which slots to fill. Relationship intelligence sits before activity/work.
 * Deal keeps compact stakeholder rows in the left rail beside profile before metrics. Contact and
 * Company keep interactive people surfaces (`related`) in the main column after pipeline work
 * because warm-path controls and the contacts card grid need width.
 * Aggregate engagement charts belong in `history`, not above decision bands.
 */
export const RECORD_DETAIL_SECTION_ORDER = [
    'identity',
    'actions',
    'notifications',
    'profile',
    'metrics',
    'relationship',
    'activity',
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
