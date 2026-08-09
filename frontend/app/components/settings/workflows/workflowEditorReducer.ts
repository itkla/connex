import type {
    WorkflowCanvas,
    WorkflowDefinition,
    WorkflowEdge,
    WorkflowExecutionMode,
    WorkflowNode,
} from "@/app/lib/types";

export type WorkflowEditorDocument = {
    name: string;
    description: string | null;
    recordType: string | null;
    executionMode: WorkflowExecutionMode;
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
};

export type WorkflowMergeConflict =
    | {
        kind: "name" | "description" | "recordType" | "executionMode";
        key: string;
        localValue: string | null;
        serverValue: string | null;
    }
    | {
        kind: "node";
        key: string;
        localValue: WorkflowNode | null;
        serverValue: WorkflowNode | null;
    }
    | {
        kind: "edge";
        key: string;
        localValue: WorkflowEdge | null;
        serverValue: WorkflowEdge | null;
    }
    | {
        kind: "position";
        key: string;
        localValue: { x: number; y: number } | null;
        serverValue: { x: number; y: number } | null;
    }
    | {
        kind: "viewport";
        key: "viewport";
        localValue: WorkflowCanvas["viewport"];
        serverValue: WorkflowCanvas["viewport"];
    };

export type WorkflowMergeResult = {
    document: WorkflowEditorDocument;
    conflicts: WorkflowMergeConflict[];
};

export type WorkflowEditorHistory = {
    past: WorkflowEditorDocument[];
    present: WorkflowEditorDocument;
    future: WorkflowEditorDocument[];
    baseline: WorkflowEditorDocument;
    transientBase: WorkflowEditorDocument | null;
};

export type WorkflowEditorAction =
    | { type: "initialize"; document: WorkflowEditorDocument }
    | { type: "replace"; document: WorkflowEditorDocument }
    | { type: "untracked"; document: WorkflowEditorDocument }
    | { type: "commit"; document: WorkflowEditorDocument }
    | { type: "commitTransient" }
    | { type: "undo" }
    | { type: "redo" }
    | { type: "rebase"; baseline: WorkflowEditorDocument; document: WorkflowEditorDocument }
    | {
        type: "markSaved";
        submittedDocument: WorkflowEditorDocument;
        document: WorkflowEditorDocument;
    };

const HISTORY_LIMIT = 50;

function equal(left: unknown, right: unknown): boolean {
    return JSON.stringify(left) === JSON.stringify(right);
}

function pushHistory(history: WorkflowEditorDocument[], document: WorkflowEditorDocument): WorkflowEditorDocument[] {
    return [...history, structuredClone(document)].slice(-HISTORY_LIMIT);
}

/** Creates an editor history around one server-saved baseline. */
export function createWorkflowEditorHistory(document: WorkflowEditorDocument): WorkflowEditorHistory {
    return {
        past: [],
        present: structuredClone(document),
        future: [],
        baseline: structuredClone(document),
        transientBase: null,
    };
}

/** Reduces semantic and coalesced editor changes into bounded undo/redo history. */
export function workflowEditorReducer(
    state: WorkflowEditorHistory,
    action: WorkflowEditorAction,
): WorkflowEditorHistory {
    switch (action.type) {
        case "initialize":
            return createWorkflowEditorHistory(action.document);
        case "replace":
            return {
                ...state,
                present: structuredClone(action.document),
                transientBase: state.transientBase ?? structuredClone(state.present),
                future: [],
            };
        case "untracked":
            return {
                ...state,
                present: structuredClone(action.document),
            };
        case "commit": {
            const historyBase = state.transientBase ?? state.present;
            if (equal(historyBase, action.document)) {
                return { ...state, present: structuredClone(action.document), transientBase: null };
            }
            return {
                ...state,
                past: pushHistory(state.past, historyBase),
                present: structuredClone(action.document),
                future: [],
                transientBase: null,
            };
        }
        case "commitTransient":
            if (!state.transientBase || equal(state.transientBase, state.present)) {
                return { ...state, transientBase: null };
            }
            return {
                ...state,
                past: pushHistory(state.past, state.transientBase),
                future: [],
                transientBase: null,
            };
        case "undo": {
            if (state.transientBase) {
                return {
                    ...state,
                    present: structuredClone(state.transientBase),
                    transientBase: null,
                };
            }
            const previous = state.past.at(-1);
            if (!previous) return state;
            return {
                ...state,
                past: state.past.slice(0, -1),
                present: structuredClone(previous),
                future: [structuredClone(state.present), ...state.future].slice(0, HISTORY_LIMIT),
            };
        }
        case "redo": {
            const next = state.future[0];
            if (!next) return state;
            return {
                ...state,
                past: pushHistory(state.past, state.present),
                present: structuredClone(next),
                future: state.future.slice(1),
                transientBase: null,
            };
        }
        case "rebase":
            return {
                past: [],
                present: structuredClone(action.document),
                future: [],
                baseline: structuredClone(action.baseline),
                transientBase: null,
            };
        case "markSaved": {
            const changedWhileSaving = !equal(state.present, action.submittedDocument);
            return {
                past: state.past,
                present: structuredClone(changedWhileSaving ? state.present : action.document),
                future: state.future,
                baseline: structuredClone(action.document),
                transientBase: changedWhileSaving ? state.transientBase : null,
            };
        }
    }
}

/** Returns whether the working document differs from its last saved server baseline. */
export function workflowDocumentIsDirty(history: WorkflowEditorHistory): boolean {
    return !equal(history.present, history.baseline);
}

function mergeEntityMap<T extends { id: string }>(
    baseValues: T[],
    localValues: T[],
    serverValues: T[],
    createConflict: (key: string, localValue: T | null, serverValue: T | null) => WorkflowMergeConflict,
): { values: T[]; conflicts: WorkflowMergeConflict[] } {
    const base = new Map(baseValues.map((value) => [value.id, value]));
    const local = new Map(localValues.map((value) => [value.id, value]));
    const server = new Map(serverValues.map((value) => [value.id, value]));
    const keys = new Set([...base.keys(), ...local.keys(), ...server.keys()]);
    const values: T[] = [];
    const conflicts: WorkflowMergeConflict[] = [];
    for (const key of [...keys].sort()) {
        const baseValue = base.get(key) ?? null;
        const localValue = local.get(key) ?? null;
        const serverValue = server.get(key) ?? null;
        const localChanged = !equal(baseValue, localValue);
        const serverChanged = !equal(baseValue, serverValue);
        if (localChanged && serverChanged && !equal(localValue, serverValue)) {
            conflicts.push(createConflict(key, localValue, serverValue));
        }
        const selected = localChanged && !serverChanged ? localValue : serverValue;
        if (selected) values.push(selected);
    }
    return { values, conflicts };
}

function mergePositionMap(
    baseValues: WorkflowCanvas["positions"],
    localValues: WorkflowCanvas["positions"],
    serverValues: WorkflowCanvas["positions"],
): { values: WorkflowCanvas["positions"]; conflicts: WorkflowMergeConflict[] } {
    const keys = new Set([...Object.keys(baseValues), ...Object.keys(localValues), ...Object.keys(serverValues)]);
    const values: WorkflowCanvas["positions"] = {};
    const conflicts: WorkflowMergeConflict[] = [];
    for (const key of [...keys].sort()) {
        const baseValue = baseValues[key] ?? null;
        const localValue = localValues[key] ?? null;
        const serverValue = serverValues[key] ?? null;
        const localChanged = !equal(baseValue, localValue);
        const serverChanged = !equal(baseValue, serverValue);
        if (localChanged && serverChanged && !equal(localValue, serverValue)) {
            conflicts.push({ kind: "position", key, localValue, serverValue });
        }
        const selected = localChanged && !serverChanged ? localValue : serverValue;
        if (selected) values[key] = selected;
    }
    return { values, conflicts };
}

/** Three-way merges a stale local draft with a newly loaded server draft by stable semantic identity. */
export function mergeWorkflowDocuments(
    base: WorkflowEditorDocument,
    local: WorkflowEditorDocument,
    server: WorkflowEditorDocument,
): WorkflowMergeResult {
    const document = structuredClone(server);
    const conflicts: WorkflowMergeConflict[] = [];
    const mergeField = (
        kind: "name" | "description" | "recordType" | "executionMode",
        baseValue: string | null,
        localValue: string | null,
        serverValue: string | null,
    ): string | null => {
        const localChanged = !equal(baseValue, localValue);
        const serverChanged = !equal(baseValue, serverValue);
        if (localChanged && serverChanged && !equal(localValue, serverValue)) {
            conflicts.push({ kind, key: kind, localValue, serverValue });
        }
        return localChanged && !serverChanged ? localValue : serverValue;
    };
    document.name = mergeField("name", base.name, local.name, server.name) ?? "";
    document.description = mergeField("description", base.description, local.description, server.description);
    document.recordType = mergeField("recordType", base.recordType, local.recordType, server.recordType);
    const executionMode = mergeField("executionMode", base.executionMode, local.executionMode, server.executionMode);
    document.executionMode = executionMode === "system" ? "system" : "user";

    const nodes = mergeEntityMap(
        base.definition.nodes,
        local.definition.nodes,
        server.definition.nodes,
        (key, localValue, serverValue) => ({ kind: "node", key, localValue, serverValue }),
    );
    const edges = mergeEntityMap(
        base.definition.edges,
        local.definition.edges,
        server.definition.edges,
        (key, localValue, serverValue) => ({ kind: "edge", key, localValue, serverValue }),
    );
    document.definition.nodes = nodes.values;
    document.definition.edges = edges.values;
    conflicts.push(...nodes.conflicts, ...edges.conflicts);

    const positions = mergePositionMap(base.canvas.positions, local.canvas.positions, server.canvas.positions);
    document.canvas.positions = positions.values;
    conflicts.push(...positions.conflicts);

    const localViewportChanged = !equal(base.canvas.viewport, local.canvas.viewport);
    const serverViewportChanged = !equal(base.canvas.viewport, server.canvas.viewport);
    if (localViewportChanged && serverViewportChanged && !equal(local.canvas.viewport, server.canvas.viewport)) {
        conflicts.push({
            kind: "viewport",
            key: "viewport",
            localValue: local.canvas.viewport,
            serverValue: server.canvas.viewport,
        });
    }
    if (localViewportChanged && !serverViewportChanged) document.canvas.viewport = local.canvas.viewport;
    return { document, conflicts };
}

/** Applies one explicit local-or-server choice to a three-way conflict result. */
export function applyWorkflowMergeChoice(
    document: WorkflowEditorDocument,
    conflict: WorkflowMergeConflict,
    choice: "local" | "server",
): WorkflowEditorDocument {
    const next = structuredClone(document);
    switch (conflict.kind) {
        case "name": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.name = value ?? "";
            break;
        }
        case "description": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.description = value;
            break;
        }
        case "recordType": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.recordType = value;
            break;
        }
        case "executionMode": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.executionMode = value === "system" ? "system" : "user";
            break;
        }
        case "node": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.definition.nodes = next.definition.nodes.filter((node) => node.id !== conflict.key);
            if (value) next.definition.nodes.push(value);
            break;
        }
        case "edge": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.definition.edges = next.definition.edges.filter((edge) => edge.id !== conflict.key);
            if (value) next.definition.edges.push(value);
            break;
        }
        case "position": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            if (value) next.canvas.positions[conflict.key] = value;
            else delete next.canvas.positions[conflict.key];
            break;
        }
        case "viewport": {
            const value = choice === "local" ? conflict.localValue : conflict.serverValue;
            next.canvas.viewport = value;
            break;
        }
    }
    return next;
}
