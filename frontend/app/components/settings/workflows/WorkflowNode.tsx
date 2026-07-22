"use client";

import { memo } from "react";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import {
    BoltIcon,
    FunnelIcon,
    PlayIcon,
    PlusIcon,
} from "@heroicons/react/24/outline";

import { cn } from "@/lib/utils";

export type WorkflowNodeData = {
    kind: "trigger" | "condition" | "action" | "add";
    label: string;
    summary: string;
    index?: number;
    selected: boolean;
    invalid: boolean;
};

export type WorkflowFlowNode = Node<WorkflowNodeData, "workflowStep">;

const KIND_ICON = {
    trigger: PlayIcon,
    condition: FunnelIcon,
    action: BoltIcon,
    add: PlusIcon,
} as const;

/**
 * One step on the linear workflow canvas. Purely presentational — selection and editing happen
 * through the inspector; the node only reflects the draft state it is derived from.
 */
function WorkflowNodeImpl({ data }: NodeProps<WorkflowFlowNode>) {
    const Icon = KIND_ICON[data.kind];
    const isAdd = data.kind === "add";
    return (
        <div
            className={cn(
                "w-72 rounded-2xl p-3.5 text-left transition-[box-shadow,border-color,color,transform] duration-150 ease-out active:scale-[0.99]",
                isAdd
                    ? "border border-dashed border-border bg-transparent text-muted-foreground hover:border-brand hover:text-foreground"
                    : "bg-card ring-1 shadow-sm",
                !isAdd && (data.invalid ? "ring-destructive" : data.selected ? "ring-2 ring-brand" : "ring-border"),
            )}
        >
            <Handle type="target" position={Position.Top} className="!size-2 !border-0 !bg-chart-axis" isConnectable={false} />
            <div className="flex items-start gap-2.5">
                <span
                    className={cn(
                        "grid size-8 shrink-0 place-items-center rounded-lg",
                        isAdd
                            ? "bg-muted text-muted-foreground"
                            : data.kind === "trigger"
                                ? "bg-brand text-brand-foreground"
                                : "bg-muted text-foreground",
                    )}
                >
                    <Icon aria-hidden className="size-4" />
                </span>
                <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-foreground">
                        {isAdd ? <span className="text-inherit">{data.label}</span> : data.label}
                    </span>
                    {!isAdd && (
                        <span className="mt-0.5 block truncate text-xs text-muted-foreground">{data.summary}</span>
                    )}
                </span>
            </div>
            <Handle type="source" position={Position.Bottom} className="!size-2 !border-0 !bg-chart-axis" isConnectable={false} />
        </div>
    );
}

export default memo(WorkflowNodeImpl);
