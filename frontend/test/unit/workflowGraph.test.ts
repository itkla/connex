import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import {
    canConnectWorkflowBranch,
    connectWorkflowBranch,
    disconnectWorkflowBranch,
    ensureScheduleEnrollment,
    insertWorkflowNode,
    removeWorkflowNode,
    topologicalWorkflowNodes,
    workflowDelayDiagnostics,
} from "@/app/components/settings/workflows/workflowGraph";
import {
    applyWorkflowMergeChoice,
    createWorkflowEditorHistory,
    mergeWorkflowDocuments,
    workflowDocumentIsDirty,
    workflowEditorReducer,
    type WorkflowEditorDocument,
} from "@/app/components/settings/workflows/workflowEditorReducer";
import type { WorkflowCanvas, WorkflowDefinition } from "@/app/lib/types";

const migratedDefinition: WorkflowDefinition = {
    schemaVersion: 1,
    entryNodeId: "trigger",
    nodes: [
        { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.won"] } },
        { id: "condition", type: "CONDITION", config: { match: "all", conditions: [{ type: "field", field: "status", op: "equals", value: "won" }] } },
        { id: "action", type: "ACTION", config: { type: "notify", title: "Won deal", body: "Review the relationship" } },
        { id: "end", type: "END" },
    ],
    edges: [
        { id: "edge-trigger", sourceNodeId: "trigger", targetNodeId: "condition", outcome: "next" },
        { id: "edge-yes", sourceNodeId: "condition", targetNodeId: "action", outcome: "yes" },
        { id: "edge-no", sourceNodeId: "condition", targetNodeId: "end", outcome: "no" },
        { id: "edge-action", sourceNodeId: "action", targetNodeId: "end", outcome: "next" },
    ],
};

const migratedCanvas: WorkflowCanvas = {
    positions: {
        trigger: { x: 80, y: 80 },
        condition: { x: 80, y: 280 },
        action: { x: 80, y: 480 },
        end: { x: 80, y: 680 },
    },
    viewport: { x: 4, y: 8, zoom: 0.9 },
};

function document(definition = migratedDefinition, canvas = migratedCanvas): WorkflowEditorDocument {
    return {
        name: "Migrated won-deal workflow",
        description: "Migrated from a linear rule",
        recordType: "deal",
        executionMode: "user",
        definition,
        canvas,
    };
}

function diagnosticMessageKeys(locale: "en" | "ja"): string[] {
    const parsed: unknown = JSON.parse(readFileSync(resolve(`messages/${locale}/workspace.json`), "utf8"));
    if (typeof parsed !== "object" || parsed === null || !("WorkspaceWorkflows" in parsed)) {
        throw new Error(`WorkspaceWorkflows is missing for ${locale}`);
    }
    const workflows = parsed.WorkspaceWorkflows;
    if (typeof workflows !== "object" || workflows === null || !("diagnostics" in workflows)) {
        throw new Error(`WorkspaceWorkflows.diagnostics is missing for ${locale}`);
    }
    const diagnostics = workflows.diagnostics;
    if (typeof diagnostics !== "object" || diagnostics === null) {
        throw new Error(`WorkspaceWorkflows.diagnostics is invalid for ${locale}`);
    }
    return Object.keys(diagnostics).sort();
}

describe("canonical workflow graph editing", () => {
    it("hydrates a migrated linear workflow without changing its wire model", () => {
        const source = document();
        const history = createWorkflowEditorHistory(source);

        expect(history.present).toEqual(source);
        expect(history.present).not.toBe(source);
        expect(workflowDocumentIsDirty(history)).toBe(false);
        expect(topologicalWorkflowNodes(history.present.definition).map((node) => node.id))
            .toEqual(["trigger", "condition", "action", "end"]);
    });

    it("inserts a condition with stable yes and no branches while preserving the previous target", () => {
        const definition: WorkflowDefinition = {
            schemaVersion: 1,
            entryNodeId: "trigger",
            nodes: [
                { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.updated"] } },
                { id: "end", type: "END" },
            ],
            edges: [{ id: "edge", sourceNodeId: "trigger", targetNodeId: "end", outcome: "next" }],
        };
        const canvas: WorkflowCanvas = {
            positions: { trigger: { x: 0, y: 0 }, end: { x: 0, y: 400 } },
            viewport: { x: 0, y: 0, zoom: 1 },
        };

        const inserted = insertWorkflowNode(definition, canvas, "trigger", "next", "CONDITION", "deal");

        expect(inserted).not.toBeNull();
        if (!inserted) return;
        const condition = inserted.definition.nodes.find((node) => node.id === inserted.insertedNodeId);
        expect(condition?.type).toBe("CONDITION");
        expect(inserted.definition.edges.find((edge) => edge.sourceNodeId === condition?.id && edge.outcome === "yes")?.targetNodeId)
            .toBe("end");
        const noEdge = inserted.definition.edges.find((edge) => edge.sourceNodeId === condition?.id && edge.outcome === "no");
        expect(inserted.definition.nodes.find((node) => node.id === noEdge?.targetNodeId)?.type).toBe("END");
    });

    it("forces a schedule trigger to target one enrollment condition", () => {
        const scheduled: WorkflowDefinition = structuredClone(migratedDefinition);
        const trigger = scheduled.nodes.find((node) => node.id === scheduled.entryNodeId);
        if (trigger?.type === "TRIGGER") trigger.config = { type: "schedule", cadence: "daily" };
        scheduled.edges = scheduled.edges.filter((edge) => edge.sourceNodeId !== "trigger");
        scheduled.edges.push({ id: "direct", sourceNodeId: "trigger", targetNodeId: "action", outcome: "next" });

        const enrolled = ensureScheduleEnrollment(scheduled, migratedCanvas, "deal");
        const nextEdge = enrolled.definition.edges.find((edge) => edge.sourceNodeId === "trigger" && edge.outcome === "next");
        expect(enrolled.definition.nodes.find((node) => node.id === nextEdge?.targetNodeId)?.type).toBe("CONDITION");
        expect(canConnectWorkflowBranch(enrolled.definition, "trigger", "next", "action")).toBe(false);
        expect(disconnectWorkflowBranch(enrolled.definition, "trigger", "next")).toBe(enrolled.definition);

        const removed = removeWorkflowNode(enrolled.definition, enrolled.canvas, enrolled.enrollmentNodeId);
        expect(removed.definition).toBe(enrolled.definition);
        expect(removed.canvas).toBe(enrolled.canvas);
    });

    it("rejects a connection that would create a cycle and preserves an edge id when retargeting", () => {
        expect(canConnectWorkflowBranch(migratedDefinition, "action", "next", "condition")).toBe(false);

        const retargeted = connectWorkflowBranch(migratedDefinition, "action", "next", "condition");
        expect(retargeted).toBe(migratedDefinition);
        const toTrigger = connectWorkflowBranch(migratedDefinition, "condition", "no", "action");
        expect(toTrigger.edges.find((edge) => edge.sourceNodeId === "condition" && edge.outcome === "no")?.id)
            .toBe("edge-no");
    });

    it("reports per-node and cumulative wait bounds with focusable field paths", () => {
        const delays = ["delay-1", "delay-2", "delay-3", "delay-4"];
        const definition: WorkflowDefinition = {
            schemaVersion: 1,
            entryNodeId: "trigger",
            nodes: [
                { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.updated"] } },
                ...delays.map((id) => ({ id, type: "DELAY" as const, config: { durationSeconds: 2_592_000 } })),
                { id: "end", type: "END" },
            ],
            edges: ["trigger", ...delays].map((sourceNodeId, index) => ({
                id: `edge-${index}`,
                sourceNodeId,
                targetNodeId: [...delays, "end"][index],
                outcome: "next" as const,
            })),
        };

        expect(workflowDelayDiagnostics(definition)).toContainEqual(expect.objectContaining({
            code: "cumulative_delay_above_maximum",
            nodeId: "delay-4",
            fieldPath: "config.durationSeconds",
        }));
    });
});

describe("workflow editor history and recovery", () => {
    it("coalesces typing into one undo entry", () => {
        let history = createWorkflowEditorHistory(document());
        history = workflowEditorReducer(history, { type: "replace", document: { ...history.present, name: "M" } });
        history = workflowEditorReducer(history, { type: "replace", document: { ...history.present, name: "My draft" } });
        history = workflowEditorReducer(history, { type: "commitTransient" });

        expect(history.past).toHaveLength(1);
        history = workflowEditorReducer(history, { type: "undo" });
        expect(history.present.name).toBe("Migrated won-deal workflow");
    });

    it("coalesces condition value typing until the field commit", () => {
        let history = createWorkflowEditorHistory(document());
        const withConditionValue = (value: string): WorkflowEditorDocument => ({
            ...history.present,
            definition: {
                ...history.present.definition,
                nodes: history.present.definition.nodes.map((node) => node.id === "condition" && node.type === "CONDITION"
                    ? {
                        ...node,
                        config: {
                            ...node.config,
                            conditions: [{ type: "field", field: "status", op: "equals", value }],
                        },
                    }
                    : node),
            },
        });

        history = workflowEditorReducer(history, { type: "replace", document: withConditionValue("c") });
        history = workflowEditorReducer(history, { type: "replace", document: withConditionValue("cold") });
        history = workflowEditorReducer(history, { type: "commitTransient" });

        expect(history.past).toHaveLength(1);
        history = workflowEditorReducer(history, { type: "undo" });
        const condition = history.present.definition.nodes.find((node) => node.id === "condition");
        expect(condition?.type === "CONDITION" ? condition.config.conditions[0].value : null).toBe("won");
    });

    it("keeps both sides of a revision conflict until an explicit choice is applied", () => {
        const base = document();
        const local = { ...document(), name: "Local name" };
        const server = { ...document(), name: "Saved name" };
        const merged = mergeWorkflowDocuments(base, local, server);

        expect(merged.conflicts).toHaveLength(1);
        const conflict = merged.conflicts[0];
        expect(conflict.kind).toBe("name");
        expect(applyWorkflowMergeChoice(merged.document, conflict, "local").name).toBe("Local name");
        expect(applyWorkflowMergeChoice(merged.document, conflict, "server").name).toBe("Saved name");
    });
});

describe("workflow diagnostic localization", () => {
    it("translates every stable backend diagnostic in English and Japanese", () => {
        const source = readFileSync(resolve("../backend/src/main/java/ooo/klae/connex/backend/dto/WorkflowDiagnosticCode.java"), "utf8");
        const match = source.match(/public enum WorkflowDiagnosticCode \{([\s\S]*?)\;/);
        if (!match) throw new Error("WorkflowDiagnosticCode enum was not found");
        const codes = match[1].split(",").map((value) => value.trim().toLowerCase()).sort();

        expect(codes).toHaveLength(69);
        expect(diagnosticMessageKeys("en")).toEqual(codes);
        expect(diagnosticMessageKeys("ja")).toEqual(codes);
    });
});
