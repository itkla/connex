import { describe, expect, it } from "vitest";

import {
    isWorkflowManualScopeValid,
    isWorkflowRecipeKey,
    offeredWorkflowRetryStep,
    retryableWorkflowStep,
    WORKFLOW_RECIPE_KEYS,
} from "@/app/lib/workflowOperations";
import type { WorkflowRunDetail, WorkflowStepRun } from "@/app/lib/types";

function step(overrides: Partial<WorkflowStepRun> = {}): WorkflowStepRun {
    return {
        sequence: 1,
        nodeId: "action-1",
        nodeType: "action",
        status: "failed",
        attempts: 1,
        retrySafety: "transactional",
        selectedOutcome: null,
        selectedEdgeId: null,
        nextNodeId: null,
        actionOutcome: null,
        actionReferenceId: null,
        startedAt: "2026-08-03T10:00:00",
        finishedAt: "2026-08-03T10:00:01",
        durationMs: 1_000,
        failure: { nodeId: "action-1", code: "execution_failed", message: "" },
        ...overrides,
    };
}

function run(path: WorkflowStepRun[] = [step()]): WorkflowRunDetail {
    return {
        runKey: "canonical-41",
        source: "canonical",
        workflowId: 7,
        status: "intervention_required",
        legacyStatus: null,
        version: null,
        execution: { mode: "user", actorUserId: 3, attributionUserId: 3 },
        trigger: { type: "manual", event: null, recordType: "deal", recordId: 9 },
        runtimeState: { waitKind: null, resumeAt: null, cancellationRequested: false },
        startedAt: "2026-08-03T10:00:00",
        finishedAt: "2026-08-03T10:00:01",
        durationMs: 1_000,
        failure: { nodeId: "action-1", code: "execution_failed", message: "" },
        stepDetailAvailable: true,
        path,
    };
}

describe("workflow operations contracts", () => {
    it("accepts every closed exact-scope kind and a resolved command-palette scope", () => {
        expect(isWorkflowManualScopeValid({ kind: "single_record", recordId: 1 })).toBe(true);
        expect(isWorkflowManualScopeValid({ kind: "page_selection", recordIds: [1, 2] })).toBe(true);
        expect(isWorkflowManualScopeValid({ kind: "explicit_selection", recordIds: [3, 4] })).toBe(true);
        expect(isWorkflowManualScopeValid({ kind: "filter_match", filter: { query: "won" } })).toBe(true);
        expect(isWorkflowManualScopeValid({
            kind: "smart_segment",
            definition: { match: "all", conditions: [{ type: "field", field: "status", op: "equals", value: "won" }] },
        })).toBe(true);
        expect(isWorkflowManualScopeValid({ kind: "saved_view", savedViewId: 8 })).toBe(true);
        expect(isWorkflowManualScopeValid({ kind: "search_snapshot", query: "Acme" })).toBe(true);
        expect(isWorkflowManualScopeValid({
            kind: "command_palette",
            resolvedScope: { kind: "saved_view", savedViewId: 8 },
        })).toBe(true);
    });

    it("rejects empty, duplicate, oversized, and invalid identifiers before preparation", () => {
        expect(isWorkflowManualScopeValid({ kind: "single_record", recordId: 0 })).toBe(false);
        expect(isWorkflowManualScopeValid({ kind: "page_selection", recordIds: [] })).toBe(false);
        expect(isWorkflowManualScopeValid({ kind: "explicit_selection", recordIds: [1, 1] })).toBe(false);
        expect(isWorkflowManualScopeValid({ kind: "saved_view", savedViewId: -1 })).toBe(false);
        expect(isWorkflowManualScopeValid({ kind: "search_snapshot", query: " " })).toBe(false);
        expect(isWorkflowManualScopeValid({
            kind: "explicit_selection",
            recordIds: Array.from({ length: 1_001 }, (_, index) => index + 1),
        })).toBe(false);
    });

    it("offers retry only from persisted safety and current failed-step evidence", () => {
        expect(retryableWorkflowStep(run())?.nodeId).toBe("action-1");
        expect(retryableWorkflowStep(run([step({ retrySafety: "deduplicated" })]))?.nodeId).toBe("action-1");
        expect(retryableWorkflowStep(run([step({ retrySafety: "none" })]))).toBeNull();
        expect(retryableWorkflowStep(run([step({ attempts: 3 })]))).toBeNull();
        expect(retryableWorkflowStep(run([
            step(),
            step({ sequence: 2, nodeId: "end-1", nodeType: "end", status: "succeeded", failure: null }),
        ]))).toBeNull();
    });

    it("keeps recipe provenance separate from persisted retry eligibility", () => {
        expect(offeredWorkflowRetryStep(run())?.retrySafety).toBe("transactional");
        expect(offeredWorkflowRetryStep(run([step({ retrySafety: "none" })]))).toBeNull();
    });

    it("ships exactly the three approved deterministic recipe keys", () => {
        expect(WORKFLOW_RECIPE_KEYS).toEqual([
            "person-job-change-follow-up",
            "deal-won-handoff",
            "cooling-company-review",
        ]);
        expect(WORKFLOW_RECIPE_KEYS.every(isWorkflowRecipeKey)).toBe(true);
        expect(isWorkflowRecipeKey("report-snapshot")).toBe(false);
    });
});
