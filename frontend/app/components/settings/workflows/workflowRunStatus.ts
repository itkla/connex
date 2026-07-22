import type { RuleExecutionStatus } from "@/app/lib/types";
import { formatDateTime, parseMysqlDateTime } from "@/app/lib/utils";

export const WORKFLOW_RUN_STATUS_CLASS: Record<RuleExecutionStatus, string> = {
    running: "border-brand/30 bg-brand-light text-foreground",
    matched: "border-border bg-secondary text-secondary-foreground",
    partial: "border-risk-medium/30 bg-risk-medium/15 text-foreground",
    skipped: "border-border bg-muted text-muted-foreground",
    failed: "border-destructive/30 bg-destructive/10 text-destructive",
};

/** Formats a UTC MySQL execution timestamp as a localized absolute time. */
export function formatWorkflowRunDateTime(value: string, locale: string): string {
    const timestamp = parseMysqlDateTime(value);
    return formatDateTime(
        Number.isNaN(timestamp) ? value : new Date(timestamp).toISOString(),
        locale,
    );
}
