import type { Deal } from '@/app/lib/types';
import { changedRecordFields } from '@/app/lib/recordEditConflict';

/** All persisted deal fields an external writer can change while an edit remains open. */
export const DEAL_CONFLICT_FIELDS = [
    'name', 'value', 'actualValue', 'valueSource', 'currency', 'pipeline', 'stage', 'position',
    'company', 'ownerId', 'expectedCloseDate', 'closedAt', 'closedReason', 'won', 'riskExcluded',
] as const satisfies readonly (keyof Deal)[];

/** Returns label keys for fields changed since this form's base snapshot. */
export function changedDealFields(base: Deal, current: Deal): (typeof DEAL_CONFLICT_FIELDS)[number][] {
    return changedRecordFields(base, current, DEAL_CONFLICT_FIELDS);
}
