import type { WorkflowManualFilter, WorkflowManualResolvedScope } from '@/app/lib/types';

/** Keeps tag- and warmth-filtered workflow selections bound to the selected contact IDs. */
export function contactWorkflowScope({
    allMatchingActive,
    activeSavedViewId,
    showArchived,
    warmthNarrowed,
    tagIds,
    recordIds,
    filter,
}: {
    allMatchingActive: boolean;
    activeSavedViewId: number | null;
    showArchived: boolean;
    warmthNarrowed: boolean;
    tagIds?: number[];
    recordIds: number[];
    filter: WorkflowManualFilter;
}): WorkflowManualResolvedScope {
    if (!allMatchingActive || warmthNarrowed || (tagIds?.length ?? 0) > 0) {
        return { kind: 'explicit_selection', recordIds };
    }
    if (activeSavedViewId !== null) return { kind: 'saved_view', savedViewId: activeSavedViewId };
    if (!showArchived) return { kind: 'filter_match', filter };
    return { kind: 'explicit_selection', recordIds };
}
