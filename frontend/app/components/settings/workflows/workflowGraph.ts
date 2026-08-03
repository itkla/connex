import { defaultAction } from "@/app/components/settings/workflows/vocabulary";
import type {
    WorkflowCanvas,
    WorkflowConditionNode,
    WorkflowDefinition,
    WorkflowDiagnostic,
    WorkflowEdge,
    WorkflowEdgeOutcome,
    WorkflowNode,
    WorkflowNodeType,
} from "@/app/lib/types";

export const WORKFLOW_NODE_LIMIT = 50;
export const WORKFLOW_EDGE_LIMIT = 100;
export const WORKFLOW_ACTION_LIMIT = 16;
export const WORKFLOW_DELAY_MIN_SECONDS = 60;
export const WORKFLOW_DELAY_MAX_SECONDS = 2_592_000;
export const WORKFLOW_DELAY_PATH_MAX_SECONDS = 7_776_000;

const EMPTY_CONDITION: WorkflowConditionNode["config"] = { match: "all", conditions: [] };

function newOpaqueId(prefix: "n" | "e"): string {
    return `${prefix}_${globalThis.crypto.randomUUID().replaceAll("-", "")}`;
}

function cloneDefinition(definition: WorkflowDefinition): WorkflowDefinition {
    return structuredClone(definition);
}

/** Returns the semantic branch outcomes exposed by a workflow node. */
export function workflowNodeOutcomes(node: WorkflowNode): WorkflowEdgeOutcome[] {
    switch (node.type) {
        case "TRIGGER":
        case "ACTION":
        case "DELAY":
            return ["next"];
        case "CONDITION":
            return ["yes", "no"];
        case "END":
            return [];
    }
}

/** Returns whether a node is the mandatory first enrollment predicate of a schedule trigger. */
export function isScheduleEnrollmentNode(definition: WorkflowDefinition, nodeId: string): boolean {
    const trigger = definition.nodes.find(
        (node) => node.id === definition.entryNodeId && node.type === "TRIGGER" && node.config.type === "schedule",
    );
    return trigger != null && definition.edges.some(
        (edge) => edge.sourceNodeId === trigger.id && edge.outcome === "next" && edge.targetNodeId === nodeId,
    );
}

/** Returns whether a branch is the mandatory schedule-trigger enrollment connection. */
export function isScheduleEnrollmentBranch(
    definition: WorkflowDefinition,
    sourceNodeId: string,
    outcome: WorkflowEdgeOutcome,
): boolean {
    const source = definition.nodes.find((node) => node.id === sourceNodeId);
    return source?.id === definition.entryNodeId
        && source.type === "TRIGGER"
        && source.config.type === "schedule"
        && outcome === "next";
}

/** Creates a typed workflow node with an incomplete, saveable default configuration. */
export function createWorkflowNode(type: WorkflowNodeType, recordType: string): WorkflowNode {
    const id = newOpaqueId("n");
    switch (type) {
        case "TRIGGER":
            return { id, type, config: { type: "entity_change", events: [] } };
        case "CONDITION":
            return { id, type, config: structuredClone(EMPTY_CONDITION) };
        case "ACTION":
            return { id, type, config: defaultAction(recordType) };
        case "DELAY":
            return { id, type, config: { durationSeconds: 3_600 } };
        case "END":
            return { id, type };
    }
}

/** Creates the smallest saveable workflow document and its separate canvas presentation. */
export function createEmptyWorkflowGraph(recordType = "deal"): {
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
} {
    const trigger = createWorkflowNode("TRIGGER", recordType);
    const end = createWorkflowNode("END", recordType);
    const edge: WorkflowEdge = {
        id: newOpaqueId("e"),
        sourceNodeId: trigger.id,
        targetNodeId: end.id,
        outcome: "next",
    };
    return {
        definition: {
            schemaVersion: 1,
            entryNodeId: trigger.id,
            nodes: [trigger, end],
            edges: [edge],
        },
        canvas: {
            positions: {
                [trigger.id]: { x: 80, y: 80 },
                [end.id]: { x: 80, y: 320 },
            },
            viewport: { x: 0, y: 0, zoom: 1 },
        },
    };
}

/** Returns nodes in deterministic topological order, with unreachable nodes ordered by id last. */
export function topologicalWorkflowNodes(definition: WorkflowDefinition): WorkflowNode[] {
    const nodeById = new Map(definition.nodes.map((node) => [node.id, node]));
    const indegree = new Map(definition.nodes.map((node) => [node.id, 0]));
    const outgoing = new Map<string, WorkflowEdge[]>();
    for (const edge of definition.edges) {
        if (!nodeById.has(edge.sourceNodeId) || !nodeById.has(edge.targetNodeId)) continue;
        indegree.set(edge.targetNodeId, (indegree.get(edge.targetNodeId) ?? 0) + 1);
        outgoing.set(edge.sourceNodeId, [...(outgoing.get(edge.sourceNodeId) ?? []), edge]);
    }
    const queue = definition.nodes
        .filter((node) => (indegree.get(node.id) ?? 0) === 0)
        .sort((left, right) => left.id.localeCompare(right.id));
    const ordered: WorkflowNode[] = [];
    while (queue.length > 0) {
        const node = queue.shift();
        if (!node) break;
        ordered.push(node);
        const nextEdges = [...(outgoing.get(node.id) ?? [])].sort((left, right) => {
            const outcomeOrder = { yes: 0, no: 1, next: 2 } satisfies Record<WorkflowEdgeOutcome, number>;
            return outcomeOrder[left.outcome] - outcomeOrder[right.outcome]
                || left.targetNodeId.localeCompare(right.targetNodeId);
        });
        for (const edge of nextEdges) {
            const nextDegree = (indegree.get(edge.targetNodeId) ?? 0) - 1;
            indegree.set(edge.targetNodeId, nextDegree);
            if (nextDegree === 0) {
                const target = nodeById.get(edge.targetNodeId);
                if (target) {
                    queue.push(target);
                    queue.sort((left, right) => left.id.localeCompare(right.id));
                }
            }
        }
    }
    const visited = new Set(ordered.map((node) => node.id));
    return [...ordered, ...definition.nodes.filter((node) => !visited.has(node.id)).sort((a, b) => a.id.localeCompare(b.id))];
}

/** Returns client-side delay diagnostics using the server's per-node and cumulative path bounds. */
export function workflowDelayDiagnostics(definition: WorkflowDefinition): WorkflowDiagnostic[] {
    const diagnostics: WorkflowDiagnostic[] = [];
    const incoming = new Map<string, WorkflowEdge[]>();
    for (const edge of definition.edges) {
        incoming.set(edge.targetNodeId, [...(incoming.get(edge.targetNodeId) ?? []), edge]);
    }
    const cumulative = new Map<string, number>();
    for (const node of topologicalWorkflowNodes(definition)) {
        const preceding = (incoming.get(node.id) ?? []).reduce(
            (maximum, edge) => Math.max(maximum, cumulative.get(edge.sourceNodeId) ?? 0),
            0,
        );
        if (node.type !== "DELAY") {
            cumulative.set(node.id, preceding);
            continue;
        }
        const duration = node.config.durationSeconds;
        if (!Number.isInteger(duration) || duration < WORKFLOW_DELAY_MIN_SECONDS) {
            diagnostics.push({
                code: "delay_duration_below_minimum",
                nodeId: node.id,
                edgeId: null,
                fieldPath: "config.durationSeconds",
                params: { minimumSeconds: String(WORKFLOW_DELAY_MIN_SECONDS) },
            });
        }
        if (duration > WORKFLOW_DELAY_MAX_SECONDS) {
            diagnostics.push({
                code: "delay_duration_above_maximum",
                nodeId: node.id,
                edgeId: null,
                fieldPath: "config.durationSeconds",
                params: { maximumSeconds: String(WORKFLOW_DELAY_MAX_SECONDS) },
            });
        }
        const total = preceding + Math.max(0, duration);
        if (total > WORKFLOW_DELAY_PATH_MAX_SECONDS) {
            diagnostics.push({
                code: "cumulative_delay_above_maximum",
                nodeId: node.id,
                edgeId: null,
                fieldPath: "config.durationSeconds",
                params: { maximumSeconds: String(WORKFLOW_DELAY_PATH_MAX_SECONDS) },
            });
        }
        cumulative.set(node.id, total);
    }
    return diagnostics;
}

function createsCycle(
    definition: WorkflowDefinition,
    sourceNodeId: string,
    targetNodeId: string,
    replacedOutcome: WorkflowEdgeOutcome,
): boolean {
    const edges = definition.edges.filter(
        (edge) => !(edge.sourceNodeId === sourceNodeId && edge.outcome === replacedOutcome),
    );
    edges.push({ id: "cycle_probe", sourceNodeId, targetNodeId, outcome: replacedOutcome });
    const outgoing = new Map<string, string[]>();
    for (const edge of edges) {
        outgoing.set(edge.sourceNodeId, [...(outgoing.get(edge.sourceNodeId) ?? []), edge.targetNodeId]);
    }
    const stack = [targetNodeId];
    const visited = new Set<string>();
    while (stack.length > 0) {
        const current = stack.pop();
        if (!current) continue;
        if (current === sourceNodeId) return true;
        if (visited.has(current)) continue;
        visited.add(current);
        stack.push(...(outgoing.get(current) ?? []));
    }
    return false;
}

/** Returns whether one semantic branch can target a node without violating local graph invariants. */
export function canConnectWorkflowBranch(
    definition: WorkflowDefinition,
    sourceNodeId: string,
    outcome: WorkflowEdgeOutcome,
    targetNodeId: string,
): boolean {
    const source = definition.nodes.find((node) => node.id === sourceNodeId);
    const target = definition.nodes.find((node) => node.id === targetNodeId);
    if (!source || !target || source.id === target.id || target.type === "TRIGGER") return false;
    if (!workflowNodeOutcomes(source).includes(outcome)) return false;
    if (source.id === definition.entryNodeId && source.type === "TRIGGER"
        && source.config.type === "schedule" && target.type !== "CONDITION") return false;
    const existing = definition.edges.some((edge) => edge.sourceNodeId === sourceNodeId && edge.outcome === outcome);
    if (!existing && definition.edges.length >= WORKFLOW_EDGE_LIMIT) return false;
    return !createsCycle(definition, sourceNodeId, targetNodeId, outcome);
}

/** Connects or retargets one semantic branch while preserving its edge identity when possible. */
export function connectWorkflowBranch(
    definition: WorkflowDefinition,
    sourceNodeId: string,
    outcome: WorkflowEdgeOutcome,
    targetNodeId: string,
): WorkflowDefinition {
    if (!canConnectWorkflowBranch(definition, sourceNodeId, outcome, targetNodeId)) return definition;
    const current = definition.edges.find((edge) => edge.sourceNodeId === sourceNodeId && edge.outcome === outcome);
    if (current?.targetNodeId === targetNodeId) return definition;
    if (!current && definition.edges.length >= WORKFLOW_EDGE_LIMIT) return definition;
    const next = cloneDefinition(definition);
    const existing = next.edges.find((edge) => edge.sourceNodeId === sourceNodeId && edge.outcome === outcome);
    if (existing) {
        existing.targetNodeId = targetNodeId;
    } else {
        next.edges.push({ id: newOpaqueId("e"), sourceNodeId, targetNodeId, outcome });
    }
    return next;
}

/** Disconnects one semantic branch without removing either node. */
export function disconnectWorkflowBranch(
    definition: WorkflowDefinition,
    sourceNodeId: string,
    outcome: WorkflowEdgeOutcome,
): WorkflowDefinition {
    if (isScheduleEnrollmentBranch(definition, sourceNodeId, outcome)) return definition;
    return {
        ...definition,
        edges: definition.edges.filter(
            (edge) => !(edge.sourceNodeId === sourceNodeId && edge.outcome === outcome),
        ),
    };
}

/** Inserts a typed node on one branch and returns the updated definition and canvas. */
export function insertWorkflowNode(
    definition: WorkflowDefinition,
    canvas: WorkflowCanvas,
    sourceNodeId: string,
    outcome: WorkflowEdgeOutcome,
    type: Exclude<WorkflowNodeType, "TRIGGER">,
    recordType: string,
): { definition: WorkflowDefinition; canvas: WorkflowCanvas; insertedNodeId: string } | null {
    if (definition.nodes.length >= WORKFLOW_NODE_LIMIT) return null;
    if (type === "ACTION" && definition.nodes.filter((node) => node.type === "ACTION").length >= WORKFLOW_ACTION_LIMIT) {
        return null;
    }
    const source = definition.nodes.find((node) => node.id === sourceNodeId);
    if (!source || !workflowNodeOutcomes(source).includes(outcome)) return null;
    if (source.id === definition.entryNodeId && source.type === "TRIGGER"
        && source.config.type === "schedule" && type !== "CONDITION") return null;
    const previousEdge = definition.edges.find(
        (edge) => edge.sourceNodeId === sourceNodeId && edge.outcome === outcome,
    );
    const addConditionEnd = type === "CONDITION" && definition.nodes.length + 1 < WORKFLOW_NODE_LIMIT;
    const edgesAdded = 1 + (previousEdge && type !== "END" ? 1 : 0) + (addConditionEnd ? 1 : 0);
    const edgesRemoved = previousEdge ? 1 : 0;
    if (definition.edges.length - edgesRemoved + edgesAdded > WORKFLOW_EDGE_LIMIT) return null;
    const node = createWorkflowNode(type, recordType);
    let nextDefinition: WorkflowDefinition = {
        ...cloneDefinition(definition),
        nodes: [...cloneDefinition(definition).nodes, node],
        edges: definition.edges.filter((edge) => edge.id !== previousEdge?.id),
    };
    nextDefinition = connectWorkflowBranch(nextDefinition, sourceNodeId, outcome, node.id);
    if (previousEdge && type !== "END") {
        const continuationOutcome: WorkflowEdgeOutcome = type === "CONDITION" ? "yes" : "next";
        nextDefinition = connectWorkflowBranch(nextDefinition, node.id, continuationOutcome, previousEdge.targetNodeId);
    }
    if (addConditionEnd) {
        const noEnd = createWorkflowNode("END", recordType);
        nextDefinition = {
            ...nextDefinition,
            nodes: [...nextDefinition.nodes, noEnd],
        };
        nextDefinition = connectWorkflowBranch(nextDefinition, node.id, "no", noEnd.id);
    }
    const sourcePosition = canvas.positions[sourceNodeId] ?? { x: 80, y: 80 };
    const targetPosition = previousEdge ? canvas.positions[previousEdge.targetNodeId] : undefined;
    const insertedPosition = targetPosition
        ? { x: (sourcePosition.x + targetPosition.x) / 2, y: (sourcePosition.y + targetPosition.y) / 2 }
        : { x: sourcePosition.x, y: sourcePosition.y + 200 };
    const positions = { ...canvas.positions, [node.id]: insertedPosition };
    const noNode = nextDefinition.nodes.find(
        (candidate) => candidate.type === "END" && !definition.nodes.some((existing) => existing.id === candidate.id) && candidate.id !== node.id,
    );
    if (noNode) positions[noNode.id] = { x: insertedPosition.x + 320, y: insertedPosition.y + 200 };
    return {
        definition: nextDefinition,
        canvas: { ...canvas, positions },
        insertedNodeId: node.id,
    };
}

/** Removes a non-entry node and safely reconnects only an unambiguous single-input path. */
export function removeWorkflowNode(
    definition: WorkflowDefinition,
    canvas: WorkflowCanvas,
    nodeId: string,
): { definition: WorkflowDefinition; canvas: WorkflowCanvas } {
    if (nodeId === definition.entryNodeId || isScheduleEnrollmentNode(definition, nodeId)) {
        return { definition, canvas };
    }
    const incoming = definition.edges.filter((edge) => edge.targetNodeId === nodeId);
    const outgoing = definition.edges.filter((edge) => edge.sourceNodeId === nodeId);
    let next: WorkflowDefinition = {
        ...definition,
        nodes: definition.nodes.filter((node) => node.id !== nodeId),
        edges: definition.edges.filter((edge) => edge.sourceNodeId !== nodeId && edge.targetNodeId !== nodeId),
    };
    if (incoming.length === 1 && outgoing.length === 1) {
        next = connectWorkflowBranch(next, incoming[0].sourceNodeId, incoming[0].outcome, outgoing[0].targetNodeId);
    }
    const positions = { ...canvas.positions };
    delete positions[nodeId];
    return { definition: next, canvas: { ...canvas, positions } };
}

/** Ensures a schedule trigger immediately targets its single enrollment Condition. */
export function ensureScheduleEnrollment(
    definition: WorkflowDefinition,
    canvas: WorkflowCanvas,
    recordType: string,
): { definition: WorkflowDefinition; canvas: WorkflowCanvas; enrollmentNodeId: string } {
    const trigger = definition.nodes.find((node) => node.id === definition.entryNodeId && node.type === "TRIGGER");
    if (!trigger) return { definition, canvas, enrollmentNodeId: definition.entryNodeId };
    const edge = definition.edges.find((candidate) => candidate.sourceNodeId === trigger.id && candidate.outcome === "next");
    const target = edge ? definition.nodes.find((node) => node.id === edge.targetNodeId) : undefined;
    if (target?.type === "CONDITION") return { definition, canvas, enrollmentNodeId: target.id };
    const inserted = insertWorkflowNode(definition, canvas, trigger.id, "next", "CONDITION", recordType);
    if (!inserted) return { definition, canvas, enrollmentNodeId: trigger.id };
    return { ...inserted, enrollmentNodeId: inserted.insertedNodeId };
}
