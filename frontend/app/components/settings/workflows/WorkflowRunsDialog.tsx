"use client";

import { useEffect, useState } from "react";
import { ClockIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import type { Rule, RuleExecution, RuleExecutionStatus } from "@/app/lib/types";
import { getRuleExecutions } from "@/app/lib/api";
import { formatDateTime } from "@/app/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

const STATUS_CLASS: Record<RuleExecutionStatus, string> = {
    running: "border-brand/30 bg-brand-light text-brand-dark",
    matched: "border-border bg-secondary text-secondary-foreground",
    partial: "border-risk-medium/30 bg-risk-medium/15 text-risk-medium",
    skipped: "border-border bg-muted text-muted-foreground",
    failed: "border-destructive/30 bg-destructive/10 text-destructive",
};

type LoadState = "loading" | "success" | "error";

/** Read-only recent execution history for a legacy-backed workflow. */
export default function WorkflowRunsDialog({
    open,
    onOpenChange,
    rule,
    workspaceId,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    rule: Rule;
    workspaceId: number;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkspaceRules");
    const locale = useLocale();
    const [executions, setExecutions] = useState<RuleExecution[]>([]);
    const [loadState, setLoadState] = useState<LoadState>("loading");
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        if (!open) return;
        const controller = new AbortController();
        setLoadState("loading");
        setExecutions([]);
        void getRuleExecutions(rule.id, { signal: controller.signal })
            .then((loaded) => {
                if (controller.signal.aborted) return;
                setExecutions(loaded);
                setLoadState("success");
            })
            .catch(() => {
                if (!controller.signal.aborted) setLoadState("error");
            });
        return () => controller.abort();
    }, [attempt, open, rule.id, workspaceId]);

    const targetLabel = (execution: RuleExecution) => {
        if (execution.triggerEntityType == null || execution.triggerEntityId == null) {
            return t("runs.scheduledTarget");
        }
        const record = recordTypeLabel(execution.triggerEntityType, tr, t("runs.recordFallback"));
        return t("runs.target", { record, id: execution.triggerEntityId });
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent
                size="xl"
                showCloseButton={false}
                className="max-h-[85dvh] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0"
            >
                <DialogHeader className="border-b border-border px-6 py-5">
                    <DialogTitle>{t("runs.title")}</DialogTitle>
                    <DialogDescription>{t("runs.description", { name: rule.name })}</DialogDescription>
                </DialogHeader>

                <div className="min-h-0 overflow-y-auto" aria-busy={loadState === "loading"} aria-live="polite">
                    {loadState === "loading" ? (
                        <RunsSkeleton />
                    ) : loadState === "error" ? (
                        <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
                            <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                                <ClockIcon className="size-5" />
                            </span>
                            <div className="space-y-1">
                                <p className="text-sm font-medium text-foreground">{t("runs.errorTitle")}</p>
                                <p className="text-sm text-muted-foreground">{t("runs.errorBody")}</p>
                            </div>
                            <Button variant="outline" size="sm" onClick={() => setAttempt((current) => current + 1)}>
                                {t("runs.retry")}
                            </Button>
                        </div>
                    ) : executions.length === 0 ? (
                        <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
                            <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                                <ClockIcon className="size-5" />
                            </span>
                            <div className="space-y-1">
                                <p className="text-sm font-medium text-foreground">{t("runs.emptyTitle")}</p>
                                <p className="text-sm text-muted-foreground">{t("runs.emptyBody")}</p>
                            </div>
                        </div>
                    ) : (
                        <ol className="divide-y divide-border">
                            {executions.map((execution) => (
                                <li key={execution.id} className="space-y-3 px-6 py-4">
                                    <div className="flex flex-wrap items-center justify-between gap-2">
                                        <Badge variant="outline" className={STATUS_CLASS[execution.status]}>
                                            {t(`runs.status.${execution.status}`)}
                                        </Badge>
                                        <time className="text-xs text-muted-foreground" dateTime={execution.executedAt}>
                                            {formatDateTime(execution.executedAt, locale)}
                                        </time>
                                    </div>
                                    <dl className="grid gap-2 text-xs sm:grid-cols-2">
                                        <div className="min-w-0 space-y-1">
                                            <dt className="text-muted-foreground">{t("runs.targetLabel")}</dt>
                                            <dd className="font-medium text-foreground">{targetLabel(execution)}</dd>
                                        </div>
                                        <div className="min-w-0 space-y-1">
                                            <dt className="text-muted-foreground">{t("runs.dedupeLabel")}</dt>
                                            <dd className="break-all font-mono text-foreground">{execution.dedupeKey}</dd>
                                        </div>
                                    </dl>
                                </li>
                            ))}
                        </ol>
                    )}
                </div>

                <DialogFooter className="border-t border-border px-6 py-4">
                    <DialogClose asChild>
                        <Button variant="outline">{t("runs.close")}</Button>
                    </DialogClose>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}

function recordTypeLabel(
    recordType: string,
    translateRecord: (key: string) => string,
    fallback: string,
): string {
    switch (recordType) {
        case "company":
        case "person":
        case "deal":
        case "task":
            return translateRecord(`record.${recordType}`);
        default:
            return fallback;
    }
}

function RunsSkeleton() {
    return (
        <div className="divide-y divide-border">
            {Array.from({ length: 4 }, (_, index) => (
                <div key={index} className="space-y-3 px-6 py-4">
                    <div className="flex items-center justify-between gap-3">
                        <Skeleton className="h-5 w-20 rounded-full" />
                        <Skeleton className="h-3 w-32" />
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                        <Skeleton className="h-8 w-full" />
                        <Skeleton className="h-8 w-full" />
                    </div>
                </div>
            ))}
        </div>
    );
}
