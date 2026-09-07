import { describe, expect, it } from "vitest";

import { canConnectWorkflowBranch, createEmptyWorkflowGraph, ensureScheduleEnrollment, insertWorkflowNode, isScheduleEnrollmentBranch, isScheduleEnrollmentNode, normalizeWorkflowForRecordType, removeWorkflowNode, workflowNodeOutcomes } from "@/app/components/settings/workflows/workflowGraph";
import { applyWorkflowMergeChoice, createWorkflowEditorHistory, mergeWorkflowDocuments, workflowDocumentIsDirty, workflowEditorReducer, type WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import { workflowDiagnosticTargetsEntry } from "@/app/components/settings/workflows/workflowDiagnosticFields";
import type { WorkflowDefinition } from "@/app/lib/types";

function taskGraph() {
    const graph = createEmptyWorkflowGraph("deal", 2, "manual");
    const inserted = insertWorkflowNode(graph.definition, graph.canvas, graph.definition.entryNodeId, "next", "ACTION", "deal");
    if (!inserted) throw new Error("Task insertion failed");
    return inserted;
}

function document(): WorkflowEditorDocument {
    return { ...createEmptyWorkflowGraph("deal", 2, "manual"), name: "Deal follow-through", description: null, recordType: "deal", executionMode: "user" };
}

describe("task completion branches", () => {
    it("waits on a dominating task and preserves the completed continuation with an explicit stopped timeout", () => {
        const graph = taskGraph();
        const continuation = graph.definition.edges.find((edge) => edge.sourceNodeId === graph.insertedNodeId)?.targetNodeId;
        const waited = insertWorkflowNode(graph.definition, graph.canvas, graph.insertedNodeId, "next", "WAIT", "deal");
        if (!waited) throw new Error("Wait insertion failed");
        const wait = waited.definition.nodes.find((node) => node.id === waited.insertedNodeId);
        expect(wait).toMatchObject({ type: "WAIT", config: { source: { nodeId: graph.insertedNodeId, output: "taskId" } } });
        if (!wait) throw new Error("Wait missing");
        expect(workflowNodeOutcomes(wait)).toEqual(["completed", "timeout"]);
        expect(waited.definition.edges.find((edge) => edge.sourceNodeId === wait.id && edge.outcome === "completed")?.targetNodeId).toBe(continuation);
        const timeout = waited.definition.edges.find((edge) => edge.sourceNodeId === wait.id && edge.outcome === "timeout");
        expect(waited.definition.nodes.find((node) => node.id === timeout?.targetNodeId)).toMatchObject({ type: "END", config: { outcome: "stopped", reason: "task_wait_timeout" } });
        expect(canConnectWorkflowBranch(waited.definition, wait.id, "completed", graph.insertedNodeId)).toBe(false);
    });

    it("does not select a task that ran on only one incoming branch", () => {
        const graph = taskGraph();
        const condition = insertWorkflowNode(graph.definition, graph.canvas, graph.insertedNodeId, "next", "CONDITION", "deal");
        if (!condition) throw new Error("Condition insertion failed");
        const branch = insertWorkflowNode(condition.definition, condition.canvas, condition.insertedNodeId, "yes", "ACTION", "deal");
        if (!branch) throw new Error("Branch task insertion failed");
        const target = branch.definition.edges.find((edge) => edge.sourceNodeId === branch.insertedNodeId)?.targetNodeId;
        const merged: WorkflowDefinition = { ...branch.definition, edges: branch.definition.edges.map((edge) => edge.sourceNodeId === condition.insertedNodeId && edge.outcome === "no" && target ? { ...edge, targetNodeId: target } : edge) };
        const next = insertWorkflowNode(merged, branch.canvas, branch.insertedNodeId, "next", "WAIT", "deal");
        expect(next?.definition.nodes.find((node) => node.id === next.insertedNodeId)).toMatchObject({ config: { source: { nodeId: branch.insertedNodeId } } });
        if (!target) throw new Error("Join missing");
        const join = { ...merged, nodes: merged.nodes.map((node) => node.id === target ? { id: node.id, type: "ACTION" as const, config: { type: "notify" } } : node) };
        const waited = insertWorkflowNode(join, branch.canvas, target, "next", "WAIT", "deal");
        expect(waited?.definition.nodes.find((node) => node.id === waited.insertedNodeId)).toMatchObject({ config: { source: { nodeId: graph.insertedNodeId } } });
    });

    it("keeps waits out of schema 1 and never silently chooses a branch when deleting a wait", () => {
        const old = createEmptyWorkflowGraph();
        expect(insertWorkflowNode(old.definition, old.canvas, old.definition.entryNodeId, "next", "WAIT", "deal")).toBeNull();
        const graph = taskGraph();
        const waited = insertWorkflowNode(graph.definition, graph.canvas, graph.insertedNodeId, "next", "WAIT", "deal");
        if (!waited) throw new Error("Wait insertion failed");
        const removed = removeWorkflowNode(waited.definition, waited.canvas, waited.insertedNodeId);
        expect(removed.definition.edges.some((edge) => edge.sourceNodeId === graph.insertedNodeId)).toBe(false);
    });
});

describe("versioned enrollment and date starts", () => {
    it("lets new schedules begin directly while preserving the legacy enrollment condition", () => {
        const graph = createEmptyWorkflowGraph("deal", 2, "schedule");
        expect(isScheduleEnrollmentBranch(graph.definition, graph.definition.entryNodeId, "next")).toBe(false);
        expect(insertWorkflowNode(graph.definition, graph.canvas, graph.definition.entryNodeId, "next", "ACTION", "deal")).not.toBeNull();
        const old = { ...graph.definition, schemaVersion: 1 as const };
        const compatibility = ensureScheduleEnrollment(old, graph.canvas, "deal");
        expect(isScheduleEnrollmentNode(compatibility.definition, compatibility.enrollmentNodeId)).toBe(true);
        expect(removeWorkflowNode(compatibility.definition, compatibility.canvas, compatibility.enrollmentNodeId).definition).toBe(compatibility.definition);
        const upgraded = { ...compatibility.definition, schemaVersion: 2 as const };
        expect(isScheduleEnrollmentNode(upgraded, compatibility.enrollmentNodeId)).toBe(true);
        const explicit = { ...upgraded, enrollment: { condition: { match: "all" as const, conditions: [] }, oneActiveRun: true, cooldownMinutes: 60 } };
        expect(isScheduleEnrollmentNode(explicit, compatibility.enrollmentNodeId)).toBe(false);
    });

    it("preserves date configuration for deals and removes unsupported date starts on record changes", () => {
        const graph = createEmptyWorkflowGraph("deal", 2, "date");
        expect(normalizeWorkflowForRecordType(graph.definition, graph.canvas, "deal").definition.nodes[0]).toMatchObject({ config: { type: "date", dateField: "expectedCloseDate", offsetDays: -30, timezone: "UTC", allowManualRuns: false } });
        expect(normalizeWorkflowForRecordType(graph.definition, graph.canvas, "company").definition.nodes[0]).toMatchObject({ config: { type: "entity_change", allowManualRuns: false } });
    });

    it("restores enrollment and stopping together on undo and detects concurrent policy changes", () => {
        const base = document();
        const local = { ...base, definition: { ...base.definition, enrollment: { oneActiveRun: true, cooldownMinutes: 1440 }, stopConditions: { match: "all" as const, conditions: [] } } };
        const changed = workflowEditorReducer(createWorkflowEditorHistory(base), { type: "commit", document: local });
        expect(workflowDocumentIsDirty(changed)).toBe(true);
        const undone = workflowEditorReducer(changed, { type: "undo" });
        expect(workflowDocumentIsDirty(undone)).toBe(false);
        expect(workflowEditorReducer(undone, { type: "redo" }).present.definition).toEqual(local.definition);
        const metadata = mergeWorkflowDocuments(base, local, { ...base, name: "Renamed" });
        expect(metadata.conflicts).toEqual([]);
        expect(metadata.document.definition.stopConditions).toEqual(local.definition.stopConditions);
        const server = { ...base, definition: { ...base.definition, enrollment: { oneActiveRun: false, cooldownMinutes: 60 } } };
        const conflict = mergeWorkflowDocuments(base, local, server);
        expect(conflict.conflicts).toHaveLength(1);
        const choice = conflict.conflicts[0];
        if (!choice) throw new Error("Policy conflict missing");
        expect(applyWorkflowMergeChoice(conflict.document, choice, "local").definition.enrollment).toEqual(local.definition.enrollment);
        expect(applyWorkflowMergeChoice(conflict.document, choice, "server").definition.stopConditions).toBeUndefined();
    });

    it("routes policy and launch-input diagnostics to the start inspector", () => {
        const diagnostic = { code: "input_definition_invalid" as const, nodeId: null, edgeId: null, params: {} };
        for (const fieldPath of ["enrollment.condition.conditions[0]", "stopConditions", "inputs[0].defaultValue"]) expect(workflowDiagnosticTargetsEntry({ ...diagnostic, fieldPath })).toBe(true);
        expect(workflowDiagnosticTargetsEntry({ ...diagnostic, fieldPath: "name" })).toBe(false);
    });
});
