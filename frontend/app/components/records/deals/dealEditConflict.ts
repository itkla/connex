import { updateDeal, updateDealValue } from '@/app/lib/api';
import type { DealDraft } from './QuickEditDealSheet';
import { requestedActualValue } from './dealOutcome';
import type { Deal, UpdateDealPayload } from '@/app/lib/types';
import { changedRecordFields } from '@/app/lib/recordEditConflict';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';

/** All persisted deal fields an external writer can change while an edit remains open. */
export const DEAL_CONFLICT_FIELDS = [
    'name', 'value', 'actualValue', 'valueSource', 'currency', 'pipeline', 'stage', 'position',
    'company', 'ownerId', 'expectedCloseDate', 'closedAt', 'closedReason', 'won', 'riskExcluded',
] as const satisfies readonly (keyof Deal)[];

/** Returns label keys for fields changed since this form's base snapshot. */
export function changedDealFields(base: Deal, current: Deal): (typeof DEAL_CONFLICT_FIELDS)[number][] {
    return changedRecordFields(base, current, DEAL_CONFLICT_FIELDS);
}

/** Captures the form fields and concurrency token from one authoritative record. */
export function toDealDraft(d: Deal): DealDraft {
    return {
        expectedVersion: d.version,
        name: d.name ?? '',
        value: d.value ?? 0,
        actualValue: d.actualValue ?? 0,
        actualValueEdited: false,
        currency: d.currency ?? 'USD',
        pipeline: d.pipeline ?? 0,
        stage: d.stage ?? 0,
        company: d.company ?? null,
        expectedCloseDate: d.expectedCloseDate ?? '',
        closedAt: d.closedAt ?? null,
        closedReason: d.closedReason ?? null,
        won: d.won ?? null,
    };
}

/** Persists one edit-sheet draft with outcome-canonicalized realized value. */
export async function submitDealDraftUpdate(dealId: number, draft: DealDraft, init: RequestInit = {}): Promise<Deal> {
    const payload: UpdateDealPayload = {
        expectedVersion: draft.expectedVersion,
        name: draft.name.trim(),
        value: draft.value,
        actualValue: requestedActualValue(draft.won, draft.actualValue, draft.actualValueEdited),
        currency: draft.currency.trim() || 'USD',
        pipeline: draft.pipeline,
        stage: draft.stage,
        company: draft.company,
        expectedCloseDate: draft.expectedCloseDate || null,
        closedAt: draft.closedAt || null,
        closedReason: draft.closedReason || null,
        won: draft.won,
    };
    return updateDeal(dealId, payload, init);
}

type DealDraftUpdate = {
    dealId: number;
    draft: DealDraft;
};

/** Persists quick-edit drafts in one captured workspace, retaining any caller-supplied origin. */
export async function submitDealDraftUpdates(updates: ReadonlyArray<DealDraftUpdate>, init?: RequestInit): Promise<Deal[]> {
    const origin = init ?? workspacePinnedRequest(renderedWorkspaceId());
    const saved: Deal[] = [];
    for (const { dealId, draft } of updates) {
        const payload: UpdateDealPayload = {
            expectedVersion: draft.expectedVersion,
            name: draft.name.trim(),
            value: draft.value,
            actualValue: requestedActualValue(draft.won, draft.actualValue, draft.actualValueEdited),
            currency: draft.currency.trim(),
            pipeline: draft.pipeline,
            stage: draft.stage,
            company: draft.company ?? null,
            expectedCloseDate: draft.expectedCloseDate || null,
            closedAt: draft.closedAt,
            closedReason: draft.closedReason,
            won: draft.won,
        };
        saved.push(await updateDeal(dealId, payload, origin));
    }
    return saved;
}


/** Keeps the complete inline-write response available to the next form opened from this row. */
export async function submitInlineDealValue(full: Deal, replace: (saved: Deal) => void): Promise<void> {
    replace(await updateDealValue(full.id, full.value ?? 0));
}

/** Retains a newer local write response until the refreshed page reaches its version. */
export function latestDealSnapshot(record: Deal, saved: Deal | null): Deal {
    return saved !== null && saved.id === record.id
        && (record.workspaceId === undefined || saved.workspaceId === undefined || record.workspaceId === saved.workspaceId)
        && saved.version !== undefined
        && (record.version === undefined || saved.version > record.version) ? saved : record;
}
