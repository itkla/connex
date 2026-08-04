"use client";

import { memo } from "react";
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";
import {
    BoltIcon,
    ClockIcon,
    FlagIcon,
    FunnelIcon,
    PlayIcon,
} from "@heroicons/react/24/outline";

import type { WorkflowEdgeOutcome, WorkflowNodeType, WorkflowStepRun } from "@/app/lib/types";
import { WORKFLOW_RUN_STATUS_CLASS, WorkflowRunStatusIcon } from "@/app/components/settings/workflows/workflowRunStatus";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export type WorkflowNodeData = {
    nodeType: WorkflowNodeType;
    label: string;
    summary: string;
    selected: boolean;
    invalid: boolean;
    readOnly: boolean;
    branchLabels: Partial<Record<WorkflowEdgeOutcome, string>>;
    inputHandleLabel: string;
    runStep?: WorkflowStepRun;
    runStatusLabel?: string;
    runTimingLabel?: string;
    runFailureLabel?: string;
};

export type WorkflowFlowNode = Node<WorkflowNodeData, "workflowNode">;

const NODE_ICON = {
    TRIGGER: PlayIcon,
    CONDITION: FunnelIcon,
    ACTION: BoltIcon,
    DELAY: ClockIcon,
    END: FlagIcon,
} satisfies Record<WorkflowNodeType, typeof PlayIcon>;

/** Compact workflow-node summary with semantic branch handles and optional run evidence. */
function WorkflowNodeImpl({ data }: NodeProps<WorkflowFlowNode>) {
    const Icon = NODE_ICON[data.nodeType];
    const outcomes: WorkflowEdgeOutcome[] = data.nodeType === "CONDITION"
        ? ["yes", "no"]
        : data.nodeType === "END"
            ? []
            : ["next"];
    return (
        <div
            className={cn(
                "relative w-72 rounded-2xl bg-card p-3.5 text-left shadow-sm ring-1",
                data.invalid ? "ring-destructive" : data.selected ? "ring-2 ring-brand" : "ring-border",
            )}
            aria-label={`${data.label}. ${data.summary}`}
        >
            {data.nodeType !== "TRIGGER" ? (
                <Handle
                    id="in"
                    type="target"
                    position={Position.Top}
                    className="!grid !size-12 !place-items-center !border-0 !bg-transparent focus-visible:!ring-2 focus-visible:!ring-ring [&.connectingto>span]:!bg-destructive [&.connectingto>span]:!ring-4 [&.connectingto>span]:!ring-destructive/20 [&.valid>span]:!bg-brand [&.valid>span]:!ring-brand/20"
                    isConnectable={!data.readOnly}
                    isConnectableStart={false}
                    aria-label={data.inputHandleLabel}
                >
                    <span className="pointer-events-none size-3 rounded-full border-2 border-card bg-chart-axis transition-colors duration-150 motion-reduce:transition-none" />
                </Handle>
            ) : null}
            <div className="flex items-start gap-2.5">
                <span
                    className={cn(
                        "grid size-8 shrink-0 place-items-center rounded-lg",
                        data.nodeType === "TRIGGER" ? "bg-brand text-brand-foreground" : "bg-muted text-foreground",
                    )}
                >
                    <Icon aria-hidden className="size-4" />
                </span>
                <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-foreground">{data.label}</span>
                    <span className="mt-0.5 block truncate text-xs text-muted-foreground" title={data.summary}>
                        {data.summary}
                    </span>
                </span>
            </div>
            {data.runStep ? (
                <div className="mt-3 space-y-1.5">
                    <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[data.runStep.status]}>
                            <WorkflowRunStatusIcon status={data.runStep.status} className="size-3" />
                            {data.runStatusLabel}
                        </Badge>
                        <span className="text-xs text-muted-foreground">{data.runTimingLabel}</span>
                    </div>
                    {data.runStep.failure ? (
                        <span className="block text-xs font-medium text-destructive">{data.runFailureLabel}</span>
                    ) : null}
                </div>
            ) : null}
            {outcomes.map((outcome, index) => (
                <div
                    key={outcome}
                    className="absolute bottom-0 flex translate-y-full flex-col items-center pt-1"
                    style={{ left: outcomes.length === 1 ? "50%" : index === 0 ? "35%" : "65%" }}
                >
                    <Handle
                        id={outcome}
                        type="source"
                        position={Position.Bottom}
                        className="!relative !inset-auto !grid !size-12 !translate-x-0 !translate-y-0 !place-items-center !border-0 !bg-transparent focus-visible:!ring-4 focus-visible:!ring-brand/20 [&.connectingfrom>span]:!bg-brand [&.connectingfrom>span]:!ring-4 [&.connectingfrom>span]:!ring-brand/20"
                        isConnectable={!data.readOnly}
                        isConnectableEnd={false}
                        aria-label={data.branchLabels[outcome]}
                    >
                        <span className="pointer-events-none size-3 rounded-full border-2 border-card bg-chart-axis transition-colors duration-150 motion-reduce:transition-none" />
                    </Handle>
                    <span className="mt-0.5 rounded bg-background px-1 text-xs font-medium text-muted-foreground">
                        {data.branchLabels[outcome]}
                    </span>
                </div>
            ))}
        </div>
    );
}

export default memo(WorkflowNodeImpl);
