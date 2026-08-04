"use client";

import { useEffect, useMemo, useRef } from "react";
import { useTranslations } from "next-intl";
import {
    BoltIcon,
    ClockIcon,
    FlagIcon,
    FunnelIcon,
    PlusIcon,
    TrashIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";

import {
    canConnectWorkflowBranch,
    isScheduleEnrollmentNode,
    isScheduleEnrollmentBranch,
    topologicalWorkflowNodes,
    workflowNodeOutcomes,
} from "@/app/components/settings/workflows/workflowGraph";
import type { WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import type {
    WorkflowDiagnostic,
    WorkflowEdgeOutcome,
    WorkflowNodeType,
    WorkflowRunDetail,
} from "@/app/lib/types";
import {
    WORKFLOW_RUN_STATUS_CLASS,
    WorkflowRunStatusIcon,
} from "@/app/components/settings/workflows/workflowRunStatus";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

const INSERT_TYPES: Array<{
    type: Exclude<WorkflowNodeType, "TRIGGER">;
    icon: typeof BoltIcon;
}> = [
    { type: "CONDITION", icon: FunnelIcon },
    { type: "ACTION", icon: BoltIcon },
    { type: "DELAY", icon: ClockIcon },
    { type: "END", icon: FlagIcon },
];

/** Deterministic, complete keyboard and mobile authoring renderer for the workflow graph. */
export default function WorkflowOutlineEditor({
    document,
    selectedNodeId,
    diagnostics,
    run,
    readOnly,
    focusNodeId,
    nodeLabel,
    nodeSummary,
    branchLabel,
    onSelectNode,
    onInsertNode,
    onConnectBranch,
    onDisconnectBranch,
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
    onSelectNode: (nodeId: string) => void;
    onInsertNode: (
        sourceNodeId: string,
        outcome: WorkflowEdgeOutcome,
        type: Exclude<WorkflowNodeType, "TRIGGER">,
    ) => void;
    onConnectBranch: (sourceNodeId: string, outcome: WorkflowEdgeOutcome, targetNodeId: string) => void;
    onDisconnectBranch: (sourceNodeId: string, outcome: WorkflowEdgeOutcome) => void;
    onDeleteNode: (nodeId: string) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const nodeRefs = useRef(new Map<string, HTMLButtonElement>());
    const ordered = useMemo(() => topologicalWorkflowNodes(document.definition), [document.definition]);
    const invalidNodeIds = useMemo(
        () => new Set(diagnostics.flatMap((diagnostic) => diagnostic.nodeId ? [diagnostic.nodeId] : [])),
        [diagnostics],
    );
    const runSteps = useMemo(() => new Map((run?.path ?? []).map((step) => [step.nodeId, step])), [run]);

    useEffect(() => {
        if (!focusNodeId) return;
        const button = nodeRefs.current.get(focusNodeId);
        button?.scrollIntoView({ block: "center" });
        button?.focus({ preventScroll: true });
    }, [focusNodeId]);

    return (
        <ol className="space-y-3" aria-label={t("outlineLabel")}>
            {ordered.map((node) => {
                const outcomes = workflowNodeOutcomes(node);
                const runStep = runSteps.get(node.id);
                const insertTypes = node.id === document.definition.entryNodeId
                    && node.type === "TRIGGER"
                    && node.config.type === "schedule"
                        ? INSERT_TYPES.filter(({ type }) => type === "CONDITION")
                        : INSERT_TYPES;
                return (
                    <li
                        key={node.id}
                        className={cn(
                            "rounded-2xl border bg-card",
                            invalidNodeIds.has(node.id)
                                ? "border-destructive"
                                : selectedNodeId === node.id
                                    ? "border-brand ring-1 ring-brand"
                                    : "border-border",
                        )}
                    >
                        <div className="flex items-start gap-3 p-3">
                            <button
                                ref={(element) => {
                                    if (element) nodeRefs.current.set(node.id, element);
                                    else nodeRefs.current.delete(node.id);
                                }}
                                type="button"
                                onClick={() => onSelectNode(node.id)}
                                aria-current={selectedNodeId === node.id ? "step" : undefined}
                                className="min-w-0 flex-1 rounded-md text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                            >
                                <span className="block text-sm font-medium text-foreground">{nodeLabel(node.id)}</span>
                                <span className="mt-0.5 block text-xs text-muted-foreground">{nodeSummary(node.id)}</span>
                            </button>
                            {runStep ? (
                                <div className="text-right">
                                    <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[runStep.status]}>
                                        <WorkflowRunStatusIcon status={runStep.status} className="size-3" />
                                        {t(`runs.status.${runStep.status}`)}
                                    </Badge>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {runStep.durationMs == null
                                            ? t("runs.durationPending")
                                            : t("runs.durationMs", { value: runStep.durationMs })}
                                    </p>
                                    {runStep.failure ? (
                                        <p className="mt-1 text-xs font-medium text-destructive">{t("runs.stepFailure")}</p>
                                    ) : null}
                                </div>
                            ) : null}
                            {!readOnly
                                && node.id !== document.definition.entryNodeId
                                && !isScheduleEnrollmentNode(document.definition, node.id) ? (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon-sm"
                                    aria-label={t("deleteNode", { name: nodeLabel(node.id) })}
                                    onClick={() => onDeleteNode(node.id)}
                                >
                                    <TrashIcon className="size-4" />
                                </Button>
                            ) : null}
                        </div>
                        {outcomes.length > 0 ? (
                            <div className="space-y-2 border-t border-border px-3 py-3">
                                {outcomes.map((outcome) => {
                                    const edge = document.definition.edges.find(
                                        (candidate) => candidate.sourceNodeId === node.id && candidate.outcome === outcome,
                                    );
                                    const mandatoryEnrollment = isScheduleEnrollmentBranch(
                                        document.definition,
                                        node.id,
                                        outcome,
                                    );
                                    return (
                                        <div key={outcome} className="grid items-center gap-2 sm:grid-cols-[minmax(5rem,auto)_minmax(0,1fr)_auto_auto]">
                                            <span className="text-xs font-medium text-foreground">{branchLabel(outcome)}</span>
                                            <Select
                                                value={edge?.targetNodeId ?? "disconnected"}
                                                onValueChange={(value) => {
                                                    if (value === "disconnected") onDisconnectBranch(node.id, outcome);
                                                    else onConnectBranch(node.id, outcome, value);
                                                }}
                                                disabled={readOnly}
                                            >
                                                <SelectTrigger
                                                    size="sm"
                                                    aria-label={t("branchTargetLabel", { branch: branchLabel(outcome) })}
                                                    className="w-full"
                                                >
                                                    <SelectValue placeholder={t("notConnected")} />
                                                </SelectTrigger>
                                                <SelectContent>
                                                    {!mandatoryEnrollment ? (
                                                        <SelectItem value="disconnected">{t("notConnected")}</SelectItem>
                                                    ) : null}
                                                    {ordered.map((target) => (
                                                        target.id === edge?.targetNodeId
                                                        || canConnectWorkflowBranch(document.definition, node.id, outcome, target.id)
                                                            ? <SelectItem key={target.id} value={target.id}>{nodeLabel(target.id)}</SelectItem>
                                                            : null
                                                    ))}
                                                </SelectContent>
                                            </Select>
                                            {!readOnly ? (
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button type="button" variant="outline" size="sm">
                                                            <PlusIcon className="size-4" />
                                                            {t("insertNode")}
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end">
                                                        {insertTypes.map(({ type, icon: Icon }) => (
                                                            <DropdownMenuItem key={type} onSelect={() => onInsertNode(node.id, outcome, type)}>
                                                                <Icon className="size-4" />
                                                                {t(`nodeType.${type.toLowerCase()}`)}
                                                            </DropdownMenuItem>
                                                        ))}
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            ) : null}
                                            {!readOnly ? (
                                                <Button
                                                    type="button"
                                                    variant="ghost"
                                                    size="icon-sm"
                                                    disabled={!edge || mandatoryEnrollment}
                                                    aria-label={t("disconnectBranch", { branch: branchLabel(outcome) })}
                                                    onClick={() => onDisconnectBranch(node.id, outcome)}
                                                >
                                                    <XMarkIcon className="size-4" />
                                                </Button>
                                            ) : null}
                                        </div>
                                    );
                                })}
                            </div>
                        ) : null}
                    </li>
                );
            })}
        </ol>
    );
}
