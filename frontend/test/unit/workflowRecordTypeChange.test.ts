import { describe, expect, it } from "vitest";

import {
    MANUAL_RUN_RECORD_TYPES,
    RECORD_TYPES,
    SCHEDULE_RECORD_TYPES,
    SIMULATION_RECORD_TYPES,
    supportsManualRun,
    supportsSimulation,
} from "@/app/components/settings/workflows/vocabulary";
import {
    createEmptyWorkflowGraph,
    ensureScheduleEnrollment,
    isScheduleEnrollmentNode,
    normalizeWorkflowForRecordType,
} from "@/app/components/settings/workflows/workflowGraph";
import type { WorkflowCanvas, WorkflowDefinition, WorkflowTriggerNode } from "@/app/lib/types";

type Graph = { definition: WorkflowDefinition; canvas: WorkflowCanvas };

function trigger(definition: WorkflowDefinition): WorkflowTriggerNode {
    const entry = definition.nodes.find((node) => node.id === definition.entryNodeId);
    if (!entry || entry.type !== "TRIGGER") throw new Error("the graph has no entry trigger");
    return entry;
}

function withTriggerConfig(graph: Graph, config: WorkflowTriggerNode["config"]): Graph {
    return {
        ...graph,
        definition: {
            ...graph.definition,
            nodes: graph.definition.nodes.map((node) =>
                node.id === graph.definition.entryNodeId && node.type === "TRIGGER"
                    ? { ...node, config }
                    : node),
        },
    };
}

function expectConnected({ definition, canvas }: Graph): void {
    const withIncoming = new Set(definition.edges.map((edge) => edge.targetNodeId));
    const ids = new Set(definition.nodes.map((node) => node.id));
    for (const node of definition.nodes) {
        if (node.id !== definition.entryNodeId) expect(withIncoming).toContain(node.id);
        expect(canvas.positions[node.id]).toBeDefined();
    }
    for (const edge of definition.edges) {
        expect(ids).toContain(edge.sourceNodeId);
        expect(ids).toContain(edge.targetNodeId);
    }
    expect(Object.keys(canvas.positions).every((nodeId) => ids.has(nodeId))).toBe(true);
}

function scheduledDealGraph(): Graph {
    const base = withTriggerConfig(createEmptyWorkflowGraph("deal"), { type: "schedule", cadence: "daily" });
    const enrolled = ensureScheduleEnrollment(base.definition, base.canvas, "deal");
    return { definition: enrolled.definition, canvas: enrolled.canvas };
}

describe("record-type change normalizes a workflow the server would otherwise reject", () => {
    it("drops the schedule trigger and its enrollment condition for a document workflow", () => {
        const before = scheduledDealGraph();
        expect(before.definition.nodes.some((node) => node.type === "CONDITION")).toBe(true);
        const enrollment = before.definition.nodes.find((node) => node.type === "CONDITION");
        const continuation = before.definition.edges.find(
            (edge) => edge.sourceNodeId === enrollment?.id && edge.outcome === "yes",
        )?.targetNodeId;

        const after = normalizeWorkflowForRecordType(before.definition, before.canvas, "document");

        expect(trigger(after.definition).config.type).toBe("entity_change");
        expect(after.definition.nodes.filter((node) => node.type === "CONDITION")).toEqual([]);
        expect(after.definition.nodes.some((node) => isScheduleEnrollmentNode(after.definition, node.id))).toBe(false);
        expect(after.definition.edges.find(
            (edge) => edge.sourceNodeId === after.definition.entryNodeId && edge.outcome === "next",
        )?.targetNodeId).toBe(continuation);
        expectConnected(after);
    });

    it("keeps the schedule and its enrollment condition for a schedulable record type", () => {
        const before = scheduledDealGraph();

        const after = normalizeWorkflowForRecordType(before.definition, before.canvas, "company");

        expect(trigger(after.definition).config.type).toBe("schedule");
        expect(trigger(after.definition).config.cadence).toBe("daily");
        expect(after.definition.nodes.filter((node) => node.type === "CONDITION")).toHaveLength(1);
    });

    it("drops events and the stage filter that belonged to the previous record type", () => {
        const before = withTriggerConfig(createEmptyWorkflowGraph("deal"), {
            type: "entity_change",
            events: ["deal.won", "deal.stage_changed"],
            targetStageId: 12,
            throttleMinutes: 30,
        });

        const after = normalizeWorkflowForRecordType(before.definition, before.canvas, "document");

        expect(trigger(after.definition).config.events).toEqual([]);
        expect(trigger(after.definition).config.targetStageId).toBeUndefined();
        expect(trigger(after.definition).config.throttleMinutes).toBe(30);
    });

    it("keeps events that the new record type still declares", () => {
        const before = withTriggerConfig(createEmptyWorkflowGraph("document"), {
            type: "entity_change",
            events: ["document.approved", "deal.won"],
        });

        const after = normalizeWorkflowForRecordType(before.definition, before.canvas, "document");

        expect(trigger(after.definition).config.events).toEqual(["document.approved"]);
    });

    it("replaces an action the new record type does not support", () => {
        const base = createEmptyWorkflowGraph("deal");
        const entryEdge = base.definition.edges.find(
            (edge) => edge.sourceNodeId === base.definition.entryNodeId,
        );
        if (!entryEdge) throw new Error("the empty graph has no entry edge");
        const withAction: Graph = {
            definition: {
                ...base.definition,
                nodes: [
                    ...base.definition.nodes,
                    { id: "n_action", type: "ACTION", config: { type: "change_stage", targetStageId: 3 } },
                ],
                edges: [
                    { ...entryEdge, targetNodeId: "n_action" },
                    { id: "e_action", sourceNodeId: "n_action", targetNodeId: entryEdge.targetNodeId, outcome: "next" },
                ],
            },
            canvas: { ...base.canvas, positions: { ...base.canvas.positions, n_action: { x: 80, y: 200 } } },
        };

        const after = normalizeWorkflowForRecordType(withAction.definition, withAction.canvas, "document");

        const action = after.definition.nodes.find((node) => node.id === "n_action");
        expect(action?.type).toBe("ACTION");
        expect(action?.type === "ACTION" ? action.config.type : null).toBe("notify");
        expectConnected(after);
    });

    it("leaves a graph untouched when its record type does not change semantics", () => {
        const before = createEmptyWorkflowGraph("deal");

        const after = normalizeWorkflowForRecordType(before.definition, before.canvas, "deal");

        expect(after.definition).toEqual(before.definition);
        expect(after.canvas).toEqual(before.canvas);
    });
});

describe("surface allowlists stay inside the record types the server accepts", () => {
    it("excludes document from manual runs and simulation", () => {
        expect(RECORD_TYPES).toContain("document");
        expect(supportsManualRun("document")).toBe(false);
        expect(supportsSimulation("document")).toBe(false);
        expect(SCHEDULE_RECORD_TYPES).not.toContain("document");
    });

    it("declares no surface record type outside the authoring vocabulary", () => {
        for (const recordType of [...MANUAL_RUN_RECORD_TYPES, ...SIMULATION_RECORD_TYPES]) {
            expect(RECORD_TYPES).toContain(recordType);
        }
    });
});
