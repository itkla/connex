"use client";

import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
    type MouseEvent as ReactMouseEvent,
    type PointerEvent as ReactPointerEvent,
} from "react";
import {
    Background,
    BackgroundVariant,
    Controls,
    ReactFlow,
    useReactFlow,
    type Connection,
    type Edge,
    type IsValidConnection,
    type OnConnectEnd,
    type OnEdgesDelete,
    type OnMoveEnd,
    type OnNodeDrag,
    type OnNodesDelete,
    type OnReconnect,
    type Viewport,
} from "@xyflow/react";
import { useTheme } from "next-themes";
import { useTranslations } from "next-intl";
import { PlusIcon } from "@heroicons/react/24/outline";
import { useReducedMotion } from "motion/react";
import "@xyflow/react/dist/style.css";

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
    canConnectWorkflowBranch,
    shouldFitWorkflowCanvasOnOpen,
    workflowNodeOutcomes,
} from "@/app/components/settings/workflows/workflowGraph";
import { toastError } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import {
    ContextMenu,
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuLabel,
    ContextMenuSeparator,
    ContextMenuTrigger,
} from "@/components/ui/context-menu";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const NODE_TYPES = { workflowNode: WorkflowNode };
type InsertNodeType = Exclude<WorkflowNodeType, "TRIGGER">;

const INSERT_TYPES: InsertNodeType[] = ["CONDITION", "ACTION", "DELAY", "END"];
const FIT_VIEW_DURATION_MS = 200;
const CONTEXT_MENU_DRAG_THRESHOLD = 8;

function isOutcome(value: string | null | undefined): value is WorkflowEdgeOutcome {
    return value === "next" || value === "yes" || value === "no";
}

function isWorkflowPaneTarget(target: EventTarget | null): boolean {
    return target instanceof globalThis.Element && target.classList.contains("react-flow__pane");
}

function useWorkflowCanvasContextMenu(
    readOnly: boolean,
    screenToFlowPosition: (position: { x: number; y: number }) => { x: number; y: number },
) {
    const positionRef = useRef<{ x: number; y: number } | null>(null);
    const activationPointRef = useRef<{ x: number; y: number } | null>(null);
    const openRef = useRef(false);
    const [open, setOpen] = useState(false);
    const capturePosition = useCallback((target: EventTarget | null, clientX: number, clientY: number) => {
        activationPointRef.current = { x: clientX, y: clientY };
        positionRef.current = !readOnly && isWorkflowPaneTarget(target)
            ? screenToFlowPosition({ x: clientX, y: clientY })
            : null;
    }, [readOnly, screenToFlowPosition]);
    const onContextMenuCapture = useCallback((event: ReactMouseEvent) => {
        capturePosition(event.target, event.clientX, event.clientY);
    }, [capturePosition]);
    const onPointerDownCapture = useCallback((event: ReactPointerEvent) => {
        if (event.pointerType === "touch" || event.pointerType === "pen") {
            capturePosition(event.target, event.clientX, event.clientY);
        } else {
            activationPointRef.current = null;
            positionRef.current = null;
        }
    }, [capturePosition]);
    const onOpenChange = useCallback((nextOpen: boolean) => {
        if (nextOpen && positionRef.current) {
            openRef.current = true;
            setOpen(true);
            return;
        }
        openRef.current = false;
        setOpen(false);
        activationPointRef.current = null;
        positionRef.current = null;
    }, []);
    useEffect(() => {
        const onOpeningPointerUp = (event: PointerEvent) => {
            const activationPoint = activationPointRef.current;
            activationPointRef.current = null;
            if (!activationPoint) return;
            const distance = Math.hypot(
                event.clientX - activationPoint.x,
                event.clientY - activationPoint.y,
            );
            if (openRef.current && distance < CONTEXT_MENU_DRAG_THRESHOLD) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }
        };
        const onOpeningPointerCancel = () => {
            activationPointRef.current = null;
        };
        globalThis.document.addEventListener("pointerup", onOpeningPointerUp, true);
        globalThis.document.addEventListener("pointercancel", onOpeningPointerCancel, true);
        return () => {
            globalThis.document.removeEventListener("pointerup", onOpeningPointerUp, true);
            globalThis.document.removeEventListener("pointercancel", onOpeningPointerCancel, true);
        };
    }, []);
    const position = useCallback(() => positionRef.current ?? undefined, []);
    return { open, onOpenChange, onContextMenuCapture, onPointerDownCapture, position };
}

function WorkflowDropdownInsertItems({
    sourceNodeId,
    outcomes,
    insertTypes,
    branchLabel,
    onInsert,
}: {
    sourceNodeId: string;
    outcomes: WorkflowEdgeOutcome[];
    insertTypes: InsertNodeType[];
    branchLabel: (outcome: WorkflowEdgeOutcome) => string;
    onInsert: (sourceNodeId: string, outcome: WorkflowEdgeOutcome, type: InsertNodeType) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    return outcomes.map((outcome, outcomeIndex) => (
        <div key={outcome}>
            {outcomeIndex > 0 ? <DropdownMenuSeparator /> : null}
            <DropdownMenuLabel>{branchLabel(outcome)}</DropdownMenuLabel>
            {insertTypes.map((type) => (
                <DropdownMenuItem key={`${outcome}-${type}`} onSelect={() => onInsert(sourceNodeId, outcome, type)}>
                    {t(`nodeType.${type.toLowerCase()}`)}
                </DropdownMenuItem>
            ))}
        </div>
    ));
}

function WorkflowContextInsertItems({
    sourceNodeId,
    outcomes,
    insertTypes,
    branchLabel,
    onInsert,
}: {
    sourceNodeId: string | null;
    outcomes: WorkflowEdgeOutcome[];
    insertTypes: InsertNodeType[];
    branchLabel: (outcome: WorkflowEdgeOutcome) => string;
    onInsert: (sourceNodeId: string, outcome: WorkflowEdgeOutcome, type: InsertNodeType) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    if (!sourceNodeId || outcomes.length === 0) {
        return <ContextMenuItem disabled>{t("selectNodeToInsert")}</ContextMenuItem>;
    }
    return outcomes.map((outcome, outcomeIndex) => (
        <div key={outcome}>
            {outcomeIndex > 0 ? <ContextMenuSeparator /> : null}
            <ContextMenuLabel>{branchLabel(outcome)}</ContextMenuLabel>
            {insertTypes.map((type) => (
                <ContextMenuItem key={`${outcome}-${type}`} onSelect={() => onInsert(sourceNodeId, outcome, type)}>
                    {t(`nodeType.${type.toLowerCase()}`)}
                </ContextMenuItem>
            ))}
        </div>
    ));
}

type WorkflowCanvasEditorProps = {
    document: WorkflowEditorDocument;
    selectedNodeId: string | null;
    diagnostics: WorkflowDiagnostic[];
    run: WorkflowRunDetail | null;
    readOnly: boolean;
    viewportLocked: boolean;
    focusNodeId: string | null;
    focusRequestId: number;
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
        type: InsertNodeType,
        position?: { x: number; y: number },
    ) => void;
    onDeleteNode: (nodeId: string) => void;
};

/** Controlled React Flow projection of a semantic workflow definition and separate canvas layout. */
export default function WorkflowCanvasEditor({
    document,
    selectedNodeId,
    diagnostics,
    run,
    readOnly,
    viewportLocked,
    focusNodeId,
    focusRequestId,
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
}: WorkflowCanvasEditorProps) {
    const t = useTranslations("WorkspaceWorkflows");
    const { resolvedTheme } = useTheme();
    const { getNode, screenToFlowPosition, setCenter } = useReactFlow();
    const reduceMotion = useReducedMotion() ?? false;
    const handledFocusRequestIdRef = useRef(focusRequestId);
    const interactionLockedRef = useRef(readOnly);
    const {
        open: contextMenuOpen,
        onOpenChange: onContextMenuOpenChange,
        onContextMenuCapture,
        onPointerDownCapture,
        position: contextPosition,
    } = useWorkflowCanvasContextMenu(readOnly, screenToFlowPosition);
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

    useLayoutEffect(() => {
        interactionLockedRef.current = readOnly;
    }, [readOnly]);

    useLayoutEffect(() => {
        const shouldFocus = focusNodeId != null && handledFocusRequestIdRef.current !== focusRequestId;
        handledFocusRequestIdRef.current = focusRequestId;
        if (!shouldFocus || viewportLocked) return;
        const node = getNode(focusNodeId);
        if (!node) return;
        void setCenter(
            node.position.x + (node.measured?.width ?? 0) / 2,
            node.position.y + (node.measured?.height ?? 0) / 2,
            { zoom: Math.max(document.canvas.viewport.zoom, 0.8), duration: 0 },
        );
        const focusFrame = requestAnimationFrame(() => {
            if (interactionLockedRef.current) return;
            const element = globalThis.document.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(focusNodeId)}"]`);
            element?.focus();
        });
        return () => cancelAnimationFrame(focusFrame);
    }, [document.canvas.viewport.zoom, focusNodeId, focusRequestId, getNode, setCenter, viewportLocked]);

    const onConnect = useCallback((connection: Connection) => {
        if (interactionLockedRef.current) return;
        if (connection.source && connection.target && isOutcome(connection.sourceHandle)) {
            onConnectBranch(connection.source, connection.sourceHandle, connection.target);
        }
    }, [onConnectBranch]);

    const isValidConnection = useCallback<IsValidConnection>((connection) => (
        !interactionLockedRef.current
        && connection.targetHandle === "in"
        && isOutcome(connection.sourceHandle)
        && canConnectWorkflowBranch(
            document.definition,
            connection.source,
            connection.sourceHandle,
            connection.target,
        )
    ), [document.definition]);

    const onConnectEnd = useCallback<OnConnectEnd>((_, connectionState) => {
        if (interactionLockedRef.current) return;
        if (connectionState.isValid === false) {
            toastError(t("invalidConnection"));
        }
    }, [t]);

    const insertAtContextPosition = useCallback((
        sourceNodeId: string,
        outcome: WorkflowEdgeOutcome,
        type: InsertNodeType,
    ) => {
        if (interactionLockedRef.current) return;
        onInsertNode(sourceNodeId, outcome, type, contextPosition());
    }, [contextPosition, onInsertNode]);

    const onNodeContextMenu = useCallback((event: ReactMouseEvent, node: WorkflowFlowNode) => {
        event.preventDefault();
        event.stopPropagation();
        if (interactionLockedRef.current) return;
        onSelectNode(node.id);
    }, [onSelectNode]);

    const onReconnect = useCallback<OnReconnect>((oldEdge, connection) => {
        if (interactionLockedRef.current) return;
        const semantic = document.definition.edges.find((edge) => edge.id === oldEdge.id);
        if (!semantic || !connection.target) return;
        onConnectBranch(semantic.sourceNodeId, semantic.outcome, connection.target);
    }, [document.definition.edges, onConnectBranch]);

    const onNodeDragStop = useCallback<OnNodeDrag<WorkflowFlowNode>>((_, node) => {
        if (!interactionLockedRef.current) onMoveNode(node.id, node.position);
    }, [onMoveNode]);

    const onNodesDelete = useCallback<OnNodesDelete<WorkflowFlowNode>>((deleted) => {
        if (!interactionLockedRef.current) deleted.forEach((node) => onDeleteNode(node.id));
    }, [onDeleteNode]);

    const onEdgesDelete = useCallback<OnEdgesDelete>((deleted) => {
        if (interactionLockedRef.current) return;
        deleted.forEach((edge) => {
            const semantic = document.definition.edges.find((candidate) => candidate.id === edge.id);
            if (semantic) onDisconnectBranch(semantic.sourceNodeId, semantic.outcome);
        });
    }, [document.definition.edges, onDisconnectBranch]);

    const onMoveEnd = useCallback<OnMoveEnd>((_, viewport) => {
        if (!interactionLockedRef.current) onMoveViewport(viewport);
    }, [onMoveViewport]);

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
                            {selectedNode ? (
                                <WorkflowDropdownInsertItems
                                    sourceNodeId={selectedNode.id}
                                    outcomes={selectedOutcomes}
                                    insertTypes={insertTypes}
                                    branchLabel={branchLabel}
                                    onInsert={onInsertNode}
                                />
                            ) : null}
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            ) : null}
            <ContextMenu open={contextMenuOpen} onOpenChange={onContextMenuOpenChange}>
                <ContextMenuTrigger asChild disabled={readOnly}>
                    <div
                        className="relative min-h-0 flex-1"
                        onContextMenuCapture={onContextMenuCapture}
                        onPointerDownCapture={onPointerDownCapture}
                    >
                        <ReactFlow
                            nodes={nodes}
                            edges={edges}
                            nodeTypes={NODE_TYPES}
                            defaultViewport={document.canvas.viewport}
                            colorMode={resolvedTheme === "dark" ? "dark" : "light"}
                            onNodeClick={(_, node) => {
                                if (!interactionLockedRef.current) onSelectNode(node.id);
                            }}
                            onNodeContextMenu={onNodeContextMenu}
                            onNodeDragStop={onNodeDragStop}
                            onNodesDelete={onNodesDelete}
                            onEdgesDelete={onEdgesDelete}
                            onMoveEnd={onMoveEnd}
                            onConnect={onConnect}
                            onConnectEnd={onConnectEnd}
                            onReconnect={onReconnect}
                            isValidConnection={isValidConnection}
                            connectionRadius={24}
                            connectionLineStyle={{ stroke: "var(--color-brand)", strokeWidth: 2 }}
                            nodesConnectable={!readOnly}
                            nodesDraggable={!readOnly}
                            edgesReconnectable={!readOnly}
                            elementsSelectable
                            multiSelectionKeyCode={null}
                            deleteKeyCode={readOnly ? null : ["Backspace", "Delete"]}
                            autoPanOnConnect={!viewportLocked}
                            autoPanOnNodeDrag={!viewportLocked}
                            autoPanOnNodeFocus={!viewportLocked}
                            autoPanOnSelection={!viewportLocked}
                            panActivationKeyCode={viewportLocked ? null : undefined}
                            zoomActivationKeyCode={viewportLocked ? null : undefined}
                            panOnDrag={!viewportLocked}
                            panOnScroll={!viewportLocked}
                            zoomOnScroll={!viewportLocked}
                            zoomOnPinch={!viewportLocked}
                            zoomOnDoubleClick={!viewportLocked}
                            fitView={fitViewOnOpen}
                            fitViewOptions={{
                                padding: 0.25,
                                maxZoom: 1,
                                duration: reduceMotion ? 0 : FIT_VIEW_DURATION_MS,
                            }}
                            proOptions={{ hideAttribution: true }}
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
                            <Background
                                variant={BackgroundVariant.Dots}
                                color="var(--color-chart-grid)"
                                gap={24}
                                size={1.25}
                            />
                            {!viewportLocked ? <Controls position="bottom-right" showInteractive={false} /> : null}
                        </ReactFlow>
                    </div>
                </ContextMenuTrigger>
                {!readOnly ? (
                    <ContextMenuContent className="w-56 motion-reduce:animate-none motion-reduce:transition-none">
                        <WorkflowContextInsertItems
                            sourceNodeId={selectedNode?.id ?? null}
                            outcomes={selectedOutcomes}
                            insertTypes={insertTypes}
                            branchLabel={branchLabel}
                            onInsert={insertAtContextPosition}
                        />
                    </ContextMenuContent>
                ) : null}
            </ContextMenu>
        </div>
    );
}
