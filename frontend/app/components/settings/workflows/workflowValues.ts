import type { WorkflowDefinition, WorkflowInputDefinition, WorkflowInputValue, WorkflowValueRef } from "@/app/lib/types";

/** Required values use the same declared defaults shown in the launch form. */
export function workflowInputsComplete(definitions: WorkflowInputDefinition[], values: Record<string, WorkflowInputValue>): boolean {
    return definitions.every((input) => {
        const value = Object.hasOwn(values, input.key) ? values[input.key] : input.defaultValue;
        if (value == null || value === "") return !input.required;
        if (input.type === "user") return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
        if (input.type === "date") return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value);
        return typeof value === "string" && (!input.required || value.trim().length > 0) && value.length <= 2_000;
    });
}

/** Stable selection keys keep reference objects out of form control values. */
export function workflowReferenceKey(ref: WorkflowValueRef): string {
    switch (ref.source) {
        case "launch_input": return `input:${ref.key}`;
        case "record_field": return `record:${ref.field}`;
        case "step_output": return `step:${ref.nodeId}:${ref.output}`;
    }
}

/** Finds prior steps present on every path to a node; branch-only outputs are never offered. */
export function workflowDominatingNodes(definition: WorkflowDefinition, nodeId: string): Set<string> {
    const all = new Set(definition.nodes.map((node) => node.id));
    const dominators = new Map(definition.nodes.map((node) => [
        node.id, node.id === definition.entryNodeId ? new Set([node.id]) : new Set(all),
    ]));
    for (let pass = 0; pass < definition.nodes.length; pass += 1) {
        let changed = false;
        for (const node of definition.nodes) {
            if (node.id === definition.entryNodeId) continue;
            const parents = definition.edges.filter((edge) => edge.targetNodeId === node.id).map((edge) => edge.sourceNodeId);
            const common = parents.length === 0 ? new Set<string>() : new Set(all);
            for (const parent of parents) {
                for (const candidate of common) if (!dominators.get(parent)?.has(candidate)) common.delete(candidate);
            }
            common.add(node.id);
            const previous = dominators.get(node.id);
            if (!previous || common.size !== previous.size || [...common].some((id) => !previous.has(id))) {
                dominators.set(node.id, common);
                changed = true;
            }
        }
        if (!changed) break;
    }
    const result = new Set(dominators.get(nodeId) ?? []);
    result.delete(nodeId);
    return result;
}
