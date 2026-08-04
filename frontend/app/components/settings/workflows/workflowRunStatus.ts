import {
    ArrowPathIcon,
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    MinusCircleIcon,
    PauseCircleIcon,
    QueueListIcon,
    XCircleIcon,
} from "@heroicons/react/24/outline";
import { createElement } from "react";

import type { WorkflowRunStatus, WorkflowRunWireStatus } from "@/app/lib/types";
import { formatDateTime, parseMysqlDateTime } from "@/app/lib/utils";

export const WORKFLOW_RUN_STATUS_CLASS: Record<WorkflowRunStatus, string> = {
    queued: "border-border bg-muted text-muted-foreground",
    running: "border-brand/30 bg-brand-light text-foreground",
    waiting: "border-risk-low/40 bg-risk-low/15 text-foreground",
    succeeded: "border-border bg-secondary text-secondary-foreground",
    failed: "border-destructive/30 bg-destructive/10 text-destructive",
    cancelled: "border-border bg-muted/60 text-muted-foreground opacity-80",
    skipped: "border-border bg-transparent text-muted-foreground",
    intervention_required: "border-risk-high/50 bg-risk-high/15 text-foreground",
};

const STATUS_ICON = {
    queued: QueueListIcon,
    running: ArrowPathIcon,
    waiting: ClockIcon,
    succeeded: CheckCircleIcon,
    failed: XCircleIcon,
    cancelled: MinusCircleIcon,
    skipped: PauseCircleIcon,
    intervention_required: ExclamationTriangleIcon,
} satisfies Record<WorkflowRunStatus, typeof ClockIcon>;

/** Normalizes retained legacy partial executions into the canonical intervention presentation. */
export function normalizeWorkflowRunStatus(status: WorkflowRunWireStatus): WorkflowRunStatus {
    return status === "partial" ? "intervention_required" : status;
}

/** Renders a shape-distinct icon for a canonical workflow run state. */
export function WorkflowRunStatusIcon({ status, className }: { status: WorkflowRunStatus; className?: string }) {
    const Icon = STATUS_ICON[status];
    return createElement(Icon, { "aria-hidden": true, className });
}

/** Formats a UTC MySQL workflow timestamp as a localized absolute time. */
export function formatWorkflowRunDateTime(value: string, locale: string): string {
    return formatDateTime(normalizeWorkflowRunDateTime(value), locale);
}

/** Normalizes a UTC MySQL workflow timestamp for machine-readable datetime attributes. */
export function normalizeWorkflowRunDateTime(value: string): string {
    const timestamp = parseMysqlDateTime(value);
    return Number.isNaN(timestamp) ? value : new Date(timestamp).toISOString();
}
