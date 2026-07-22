import type { RuleExecutionStatus } from "@/app/lib/types";

export const WORKFLOW_RUN_STATUS_CLASS: Record<RuleExecutionStatus, string> = {
    running: "border-brand/30 bg-brand-light text-foreground",
    matched: "border-border bg-secondary text-secondary-foreground",
    partial: "border-risk-medium/30 bg-risk-medium/15 text-foreground",
    skipped: "border-border bg-muted text-muted-foreground",
    failed: "border-destructive/30 bg-destructive/10 text-destructive",
};
