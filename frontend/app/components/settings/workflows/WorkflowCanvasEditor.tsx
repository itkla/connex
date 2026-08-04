"use client";

import { useCallback, useEffect, useMemo } from "react";
import {
    Controls,
    ReactFlow,
    useReactFlow,
    type Connection,
    type Edge,
    type OnReconnect,
    type Viewport,
} from "@xyflow/react";
import { useTheme } from "next-themes";
import { useTranslations } from "next-intl";
import { PlusIcon } from "@heroicons/react/24/outline";
import { useReducedMotion } from "motion/react";

import WorkflowNode, { type WorkflowFlowNode } from "@/app/components/settings/workflows/WorkflowNode";
import type {
    WorkflowDiagnostic,
    WorkflowEdgeOutcome,
    WorkflowNodeType,
    WorkflowRunDetail,
} from "@/app/lib/types";
import type { WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import {
    isScheduleEnrollmentNode,
    isScheduleEnrollmentBranch,
    shouldFitWorkflowCanvasOnOpen,
    workflowNodeOutcomes,
} from "@/app/components/settings/workflows/workflowGraph";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const NODE_TYPES = { workflowNode: WorkflowNode };
const INSERT_TYPES: Array<Exclude<WorkflowNodeType, "TRIGGER">> = ["CONDITION", "ACTION", "DELAY", "END"];
const FIT_VIEW_DURATION_MS = 200;

function isOutcome(value: string | null | undefined): value is WorkflowEdgeOutcome {
    return value === "next" || value === "yes" || value === "no";
}

/** Controlled React Flow projection of a semantic workflow definition and separate canvas layout. */
export default function WorkflowCanvasEditor({
    document,
    selectedNodeId,
    diagnostics,
    run,
    readOnly,
    focusNodeId,
    nodeLabel,
    nodeSummary,
    branchLabel,
    runStatusLabel,
    onSelectNode,
    onMoveNode,
    onMoveViewport,
    onConnectBranch,
    onDisconnectBranch,
    onInsertNode,
    onDeleteNode,
}: {
    document: WorkflowEditorDocument;
    selectedNodeId: string | null;
    diagnostics: WorkflowDiagnostic[];
    run: WorkflowRunDetail | null;
    readOnly: boolean;
    focusNodeId: string | null;
    nodeLabel: (nodeId: string) => string;
    nodeSummary: (nodeId: string) => string;
    branchLabel: (outcome: WorkflowEdgeOutcome) => string;
    runStatusLabel: (status: string) => string;
    onSelectNode: (nodeId: string) => void;
    onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
    onMoveViewport: (viewport: Viewport) => void;
    onConnectBranch: (sourceNodeId: string, outcome: WorkflowEdgeOutcome, targetNodeId: string) => void;
    onDisconnectBranch: (sourceNodeId: string, outcome: WorkflowEdgeOutcome) => void;
    onInsertNode: (
        sourceNodeId: string,
        outcome: WorkflowEdgeOutcome,
        type: Exclude<WorkflowNodeType, "TRIGGER">,
    ) => void;
    onDeleteNode: (nodeId: string) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const { resolvedTheme } = useTheme();
    const { getNode, setCenter } = useReactFlow();
    const reduceMotion = useReducedMotion() ?? false;
    const invalidNodeIds = useMemo(
        () => new Set(diagnostics.flatMap((diagnostic) => diagnostic.nodeId ? [diagnostic.nodeId] : [])),
        [diagnostics],
    );
    const runSteps = useMemo(
        () => new Map((run?.path ?? []).map((step) => [step.nodeId, step])),
        [run],
    );
    const selectedNode = document.definition.nodes.find((node) => node.id === selectedNodeId);
    const selectedOutcomes = selectedNode ? workflowNodeOutcomes(selectedNode) : [];
    const fitViewOnOpen = shouldFitWorkflowCanvasOnOpen(document.definition, document.canvas);
    const insertTypes = selectedNode?.id === document.definition.entryNodeId
        && selectedNode.type === "TRIGGER"
        && selectedNode.config.type === "schedule"
            ? INSERT_TYPES.filter((type) => type === "CONDITION")
            : INSERT_TYPES;
    const nodes = useMemo<WorkflowFlowNode[]>(() => document.definition.nodes.map((node) => {
        const runStep = runSteps.get(node.id);
        return {
            id: node.id,
            type: "workflowNode",
            position: document.canvas.positions[node.id] ?? { x: 80, y: 80 },
            draggable: !readOnly,
            selectable: true,
            deletable: !readOnly
                && node.id !== document.definition.entryNodeId
                && !isScheduleEnrollmentNode(document.definition, node.id),
            data: {
                nodeType: node.type,
                label: nodeLabel(node.id),
                summary: nodeSummary(node.id),
                selected: selectedNodeId === node.id,
                invalid: invalidNodeIds.has(node.id),
                readOnly,
                branchLabels: {
                    next: branchLabel("next"),
                    yes: branchLabel("yes"),
                    no: branchLabel("no"),
                },
                inputHandleLabel: t("canvasA11y.inputHandle"),
                runStep,
                runStatusLabel: runStep ? runStatusLabel(runStep.status) : undefined,
                runTimingLabel: runStep
                    ? runStep.durationMs == null
                        ? t("runs.durationPending")
                        : t("runs.durationMs", { value: runStep.durationMs })
                    : undefined,
                runFailureLabel: runStep?.failure ? t("runs.stepFailure") : undefined,
            },
        };
    }), [
        branchLabel,
        document.canvas.positions,
        document.definition,
        invalidNodeIds,
        nodeLabel,
        nodeSummary,
        readOnly,
        runStatusLabel,
        runSteps,
        selectedNodeId,
        t,
    ]);
    const edges = useMemo<Edge[]>(() => document.definition.edges.map((edge) => {
        const traversed = run?.path.some((step) => step.selectedEdgeId === edge.id) ?? false;
        const mandatoryEnrollment = isScheduleEnrollmentBranch(
            document.definition,
            edge.sourceNodeId,
            edge.outcome,
        );
        return {
            id: edge.id,
            source: edge.sourceNodeId,
            target: edge.targetNodeId,
            sourceHandle: edge.outcome,
            targetHandle: "in",
            type: "smoothstep",
            reconnectable: readOnly ? false : "target",
            deletable: !readOnly && !mandatoryEnrollment,
            selectable: !readOnly,
            style: {
                stroke: traversed ? "var(--color-brand)" : "var(--color-chart-grid)",
                strokeWidth: traversed ? 2.5 : 1.5,
            },
        };
    }), [document.definition, readOnly, run?.path]);

    useEffect(() => {
        if (!focusNodeId) return;
        const node = getNode(focusNodeId);
        if (!node) return;
        void setCenter(
            node.position.x + (node.measured?.width ?? 0) / 2,
            node.position.y + (node.measured?.height ?? 0) / 2,
            { zoom: Math.max(document.canvas.viewport.zoom, 0.8), duration: 0 },
        );
        requestAnimationFrame(() => {
            const element = globalThis.document.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(focusNodeId)}"]`);
            element?.focus();
        });
    }, [document.canvas.viewport.zoom, focusNodeId, getNode, setCenter]);

    const onConnect = useCallback((connection: Connection) => {
        if (connection.source && connection.target && isOutcome(connection.sourceHandle)) {
            onConnectBranch(connection.source, connection.sourceHandle, connection.target);
        }
    }, [onConnectBranch]);

    const onReconnect = useCallback<OnReconnect>((oldEdge, connection) => {
        const semantic = document.definition.edges.find((edge) => edge.id === oldEdge.id);
        if (!semantic || !connection.target) return;
        onConnectBranch(semantic.sourceNodeId, semantic.outcome, connection.target);
    }, [document.definition.edges, onConnectBranch]);

    return (
        <div className="flex h-full min-h-[32rem] flex-col overflow-hidden rounded-2xl border border-border bg-muted/20">
            {!readOnly ? (
                <div className="flex shrink-0 items-center border-b border-border bg-background/80 px-3 py-2">
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <Button variant="outline" size="sm" disabled={!selectedNode || selectedOutcomes.length === 0}>
                                <PlusIcon className="size-4" />
                                {t("insertNode")}
                            </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" className="w-56">
                            {selectedOutcomes.map((outcome, outcomeIndex) => (
                                <div key={outcome}>
                                    {outcomeIndex > 0 ? <DropdownMenuSeparator /> : null}
                                    <DropdownMenuLabel>{branchLabel(outcome)}</DropdownMenuLabel>
                                    {insertTypes.map((type) => (
                                        <DropdownMenuItem
                                            key={`${outcome}-${type}`}
                                            onSelect={() => {
                                                if (selectedNode) onInsertNode(selectedNode.id, outcome, type);
                                            }}
                                        >
                                            {t(`nodeType.${type.toLowerCase()}`)}
                                        </DropdownMenuItem>
                                    ))}
                                </div>
                            ))}
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            ) : null}
            <div className="relative min-h-0 flex-1">
                <ReactFlow
                    nodes={nodes}
                    edges={edges}
                    nodeTypes={NODE_TYPES}
                    defaultViewport={document.canvas.viewport}
                    colorMode={resolvedTheme === "dark" ? "dark" : "light"}
                    onNodeClick={(_, node) => onSelectNode(node.id)}
                    onNodeDragStop={(_, node) => onMoveNode(node.id, node.position)}
                    onNodesDelete={(deleted) => deleted.forEach((node) => onDeleteNode(node.id))}
                    onEdgesDelete={(deleted) => deleted.forEach((edge) => {
                        const semantic = document.definition.edges.find((candidate) => candidate.id === edge.id);
                        if (semantic) onDisconnectBranch(semantic.sourceNodeId, semantic.outcome);
                    })}
                    onMoveEnd={(_, viewport) => onMoveViewport(viewport)}
                    onConnect={onConnect}
                    onReconnect={onReconnect}
                    nodesConnectable={!readOnly}
                    nodesDraggable={!readOnly}
                    edgesReconnectable={!readOnly}
                    elementsSelectable
                    multiSelectionKeyCode={null}
                    deleteKeyCode={readOnly ? null : ["Backspace", "Delete"]}
                    panOnScroll
                    fitView={fitViewOnOpen}
                    fitViewOptions={{
                        padding: 0.25,
                        maxZoom: 1,
                        duration: reduceMotion ? 0 : FIT_VIEW_DURATION_MS,
                    }}
                    proOptions={{ hideAttribution: false }}
                    ariaLabelConfig={{
                        "node.a11yDescription.default": t("canvasA11y.nodeDescription"),
                        "node.a11yDescription.keyboardDisabled": t("canvasA11y.nodeKeyboardDisabled"),
                        "node.a11yDescription.ariaLiveMessage": ({ x, y }) => t("canvasA11y.nodeMoved", { x, y }),
                        "edge.a11yDescription.default": t("canvasA11y.edgeDescription"),
                        "controls.ariaLabel": t("canvasA11y.controls"),
                        "controls.zoomIn.ariaLabel": t("canvasA11y.zoomIn"),
                        "controls.zoomOut.ariaLabel": t("canvasA11y.zoomOut"),
                        "controls.fitView.ariaLabel": t("canvasA11y.fitView"),
                        "controls.interactive.ariaLabel": t("canvasA11y.interactive"),
                        "minimap.ariaLabel": t("canvasA11y.minimap"),
                        "handle.ariaLabel": t("canvasA11y.handle"),
                    }}
                >
                    <Controls position="bottom-right" showInteractive={false} />
                </ReactFlow>
            </div>
        </div>
    );
}
