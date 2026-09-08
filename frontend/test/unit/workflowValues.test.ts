import { describe, expect, it } from "vitest";

import { workflowDominatingNodes, workflowInputsComplete } from "@/app/components/settings/workflows/workflowValues";
import { createEmptyWorkflowGraph, normalizeWorkflowForRecordType } from "@/app/components/settings/workflows/workflowGraph";
import { applyWorkflowMergeChoice, createWorkflowEditorHistory, mergeWorkflowDocuments, workflowDocumentIsDirty, workflowEditorReducer, type WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import type { WorkflowDefinition, WorkflowInputDefinition } from "@/app/lib/types";

const inputs: WorkflowInputDefinition[] = [
    { key: "assignee", label: "Assignee", type: "user", required: true },
    { key: "note", label: "Note", type: "text", required: true, defaultValue: "Review the company" },
    { key: "dueDate", label: "Due date", type: "date", required: false },
];

describe("manual workflow inputs", () => {
    it("uses declared defaults and distinguishes a deliberately cleared value", () => {
        expect(workflowInputsComplete(inputs, {})).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: 7 })).toBe(true);
        expect(workflowInputsComplete(inputs, { assignee: 7, note: null })).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: 7, dueDate: "2026-09-15" })).toBe(true);
    });

    it("rejects invalid target types and missing required text before preparation", () => {
        expect(workflowInputsComplete(inputs, { assignee: "7" })).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: -1 })).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: 1.5 })).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: 7, note: " " })).toBe(false);
        expect(workflowInputsComplete(inputs, { assignee: 7, dueDate: "next week" })).toBe(false);
        const optionalText: WorkflowInputDefinition[] = [{ key: "context", label: "Context", type: "text", required: false }];
        expect(workflowInputsComplete(optionalText, { context: " " })).toBe(true);
        expect(workflowInputsComplete(optionalText, { context: " ".repeat(2_001) })).toBe(false);
    });
});

describe("workflow output availability", () => {
    it("offers only prior steps present on every incoming path", () => {
        const definition: WorkflowDefinition = {
            schemaVersion: 2, entryNodeId: "start",
            nodes: [
                { id: "start", type: "TRIGGER", config: { type: "manual" } },
                { id: "firstTask", type: "ACTION", config: { type: "create_task" } },
                { id: "condition", type: "CONDITION", config: { match: "all", conditions: [] } },
                { id: "branchTask", type: "ACTION", config: { type: "create_task" } },
                { id: "nextTask", type: "ACTION", config: { type: "create_task" } },
            ],
            edges: [
                { id: "1", sourceNodeId: "start", targetNodeId: "firstTask", outcome: "next" },
                { id: "2", sourceNodeId: "firstTask", targetNodeId: "condition", outcome: "next" },
                { id: "3", sourceNodeId: "condition", targetNodeId: "branchTask", outcome: "yes" },
                { id: "4", sourceNodeId: "condition", targetNodeId: "nextTask", outcome: "no" },
                { id: "5", sourceNodeId: "branchTask", targetNodeId: "nextTask", outcome: "next" },
            ],
        };
        expect(workflowDominatingNodes(definition, "nextTask")).toEqual(new Set(["start", "firstTask", "condition"]));
        expect(workflowDominatingNodes(definition, "branchTask").has("firstTask")).toBe(true);
    });
});

describe("versioned workflow authoring", () => {
    it("keeps v1 automatic defaults unchanged and requires explicit manual opt-in for new automatic graphs", () => {
        const existing = createEmptyWorkflowGraph("person");
        const automatic = createEmptyWorkflowGraph("person", 2);
        expect(existing.definition.schemaVersion).toBe(1);
        expect(existing.definition.nodes[0]).toMatchObject({ config: { type: "entity_change", events: [] } });
        expect(automatic.definition.nodes[0]).toMatchObject({ config: { type: "entity_change", allowManualRuns: false } });
    });

    it("keeps manual starts when changing between supported primary records", () => {
        const graph = createEmptyWorkflowGraph("person", 2, "manual");
        for (const type of ["person", "company", "deal"]) {
            const changed = normalizeWorkflowForRecordType(graph.definition, graph.canvas, type);
            expect(changed.definition.nodes[0]).toMatchObject({ config: { type: "manual" } });
        }
    });

    it("tracks launch-input edits in dirty state and restores them with undo and redo", () => {
        const graph = createEmptyWorkflowGraph("person", 2, "manual");
        const document: WorkflowEditorDocument = { ...graph, name: "Review", description: null, recordType: "person", executionMode: "user" };
        const baseline = createWorkflowEditorHistory(document);
        const changed = workflowEditorReducer(baseline, { type: "commit", document: { ...document, definition: { ...document.definition, inputs } } });
        expect(workflowDocumentIsDirty(changed)).toBe(true);
        const undone = workflowEditorReducer(changed, { type: "undo" });
        expect(workflowDocumentIsDirty(undone)).toBe(false);
        expect(undone.present.definition.inputs).toBeUndefined();
        const redone = workflowEditorReducer(undone, { type: "redo" });
        expect(redone.present.definition.inputs).toEqual(inputs);
    });

    it("merges local launch inputs alongside an independent server metadata change", () => {
        const graph = createEmptyWorkflowGraph("person", 2, "manual");
        const base: WorkflowEditorDocument = { ...graph, name: "Review", description: null, recordType: "person", executionMode: "user" };
        const local = { ...base, definition: { ...base.definition, inputs } };
        const server = { ...base, name: "Renamed review" };
        const merged = mergeWorkflowDocuments(base, local, server);
        expect(merged.conflicts).toEqual([]);
        expect(merged.document.definition.inputs).toEqual(inputs);
        expect(merged.document.name).toBe("Renamed review");
    });

    it("requires an explicit choice when launch-input definitions conflict", () => {
        const graph = createEmptyWorkflowGraph("person", 2, "manual");
        const base: WorkflowEditorDocument = { ...graph, name: "Review", description: null, recordType: "person", executionMode: "user" };
        const local = { ...base, definition: { ...base.definition, inputs } };
        const server = { ...base, definition: { ...base.definition, inputs: inputs.slice(0, 1) } };
        const merged = mergeWorkflowDocuments(base, local, server);
        expect(merged.conflicts).toHaveLength(1);
        const conflict = merged.conflicts[0];
        if (!conflict) throw new Error("Expected an input conflict");
        expect(applyWorkflowMergeChoice(merged.document, conflict, "local").definition.inputs).toEqual(inputs);
        expect(applyWorkflowMergeChoice(merged.document, conflict, "server").definition.inputs).toEqual(inputs.slice(0, 1));
    });
});
