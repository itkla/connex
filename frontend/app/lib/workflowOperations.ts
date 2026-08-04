import type {
    WorkflowManualResolvedScope,
    WorkflowManualScope,
    WorkflowRunDetail,
    WorkflowStepRun,
} from "@/app/lib/types";

export const WORKFLOW_RECIPE_KEYS = [
    "person-job-change-follow-up",
    "deal-won-handoff",
    "cooling-company-review",
] as const;

/** Whether a recipe key belongs to the immutable curated register exposed by this client. */
export function isWorkflowRecipeKey(value: string): value is (typeof WORKFLOW_RECIPE_KEYS)[number] {
    return WORKFLOW_RECIPE_KEYS.some((key) => key === value);
}

function hasValidRecordIds(recordIds: number[]): boolean {
    return recordIds.length > 0
        && recordIds.length <= 1_000
        && recordIds.every((recordId) => Number.isInteger(recordId) && recordId > 0)
        && new Set(recordIds).size === recordIds.length;
}

/** Validates the closed, bounded exact-scope shape before asking the server to freeze it. */
export function isWorkflowManualResolvedScopeValid(scope: WorkflowManualResolvedScope): boolean {
    switch (scope.kind) {
        case "single_record":
            return Number.isInteger(scope.recordId) && scope.recordId > 0;
        case "page_selection":
        case "explicit_selection":
            return hasValidRecordIds(scope.recordIds);
        case "filter_match":
            return true;
        case "smart_segment":
            return scope.definition.conditions.length > 0 || (scope.definition.groups?.length ?? 0) > 0;
        case "saved_view":
            return Number.isInteger(scope.savedViewId) && scope.savedViewId > 0;
        case "search_snapshot":
            return scope.query.trim().length > 0 && scope.query.trim().length <= 200;
    }
}

/** Validates a manual scope and requires command-palette entry to contain a non-command resolved scope. */
export function isWorkflowManualScopeValid(scope: WorkflowManualScope): boolean {
    return scope.kind === "command_palette"
        ? isWorkflowManualResolvedScopeValid(scope.resolvedScope)
        : isWorkflowManualResolvedScopeValid(scope);
}

/** Determines whether the server-authoritative retry endpoint can be offered for the current evidence. */
export function retryableWorkflowStep(run: WorkflowRunDetail): WorkflowStepRun | null {
    if (run.source !== "canonical" || run.status !== "intervention_required" || !run.failure?.nodeId) return null;
    const failedIndex = run.path.findIndex((step) => step.nodeId === run.failure?.nodeId && step.status === "failed");
    if (failedIndex < 0) return null;
    const step = run.path[failedIndex];
    if (!step || step.nodeType !== "action" || step.retrySafety === "none" || step.attempts >= 3) return null;
    return run.path.slice(failedIndex + 1).some((later) => later.status === "succeeded") ? null : step;
}

/** Offers retry solely from the persisted per-step classification and current ledger evidence. */
export function offeredWorkflowRetryStep(run: WorkflowRunDetail): WorkflowStepRun | null {
    return retryableWorkflowStep(run);
}
