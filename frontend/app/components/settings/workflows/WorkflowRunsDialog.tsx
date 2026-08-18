"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ClockIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import { getWorkflowRun, getWorkflowRuns } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import type { WorkflowRunDetail, WorkflowRunSummary } from "@/app/lib/types";
import {
    formatWorkflowRunDateTime,
    normalizeWorkflowRunStatus,
    normalizeWorkflowRunDateTime,
    WORKFLOW_RUN_STATUS_CLASS,
    WorkflowRunStatusIcon,
} from "@/app/components/settings/workflows/workflowRunStatus";
import { RECORD_TYPES } from "@/app/components/settings/workflows/vocabulary";
import WorkflowRunReference from "@/app/components/settings/workflows/WorkflowRunReference";
import { workflowRunNumber } from "@/app/components/settings/workflows/workflowRunKey";
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

type LoadState = "loading" | "success" | "error";
type PendingState = "idle" | "loading";

/** A workflow's full run history, with step detail wherever it was recorded. */
export default function WorkflowRunsDialog({
    open,
    onOpenChange,
    workflowId,
    workflowName,
    workspaceId,
    onSelectRun,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    workflowId: number;
    workflowName: string;
    workspaceId: number;
    onSelectRun?: (run: WorkflowRunDetail) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkflowAuthoring");
    const locale = useLocale();
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [loadState, setLoadState] = useState<LoadState>("loading");
    const [attempt, setAttempt] = useState(0);
    const [selected, setSelected] = useState<WorkflowRunDetail | null>(null);
    const [detailLoadState, setDetailLoadState] = useState<PendingState>("idle");
    const [moreLoading, setMoreLoading] = useState(false);
    const detailControllerRef = useRef<AbortController | null>(null);
    const workspaceHeaders = useMemo(() => ({ "X-Workspace-Id": String(workspaceId) }), [workspaceId]);
    const detailLoading = detailLoadState === "loading";

    useEffect(() => {
        if (!open) return;
        const controller = new AbortController();
        let active = true;
        void (async () => {
            setLoadState("loading");
            try {
                const page = await getWorkflowRuns(workflowId, 50, undefined, {
                    signal: controller.signal,
                    headers: workspaceHeaders,
                });
                if (!active || controller.signal.aborted) return;
                setRuns(page.items);
                setNextCursor(page.nextCursor);
                setLoadState("success");
            } catch {
                if (active && !controller.signal.aborted) setLoadState("error");
            }
        })();
        return () => {
            active = false;
            controller.abort();
        };
    }, [attempt, open, workflowId, workspaceHeaders]);

    useEffect(() => () => detailControllerRef.current?.abort(), []);

    const loadDetail = async (run: WorkflowRunSummary) => {
        if (!run.stepDetailAvailable) return;
        detailControllerRef.current?.abort();
        const controller = new AbortController();
        detailControllerRef.current = controller;
        setDetailLoadState("loading");
        try {
            const detail = await getWorkflowRun(workflowId, run.runKey, {
                signal: controller.signal,
                headers: workspaceHeaders,
            });
            if (!controller.signal.aborted) setSelected(detail);
        } catch {
            if (!controller.signal.aborted) toastError(t("runs.detailFailed"));
        } finally {
            if (!controller.signal.aborted) setDetailLoadState("idle");
        }
    };

    const loadMore = async () => {
        if (!nextCursor || moreLoading) return;
        setMoreLoading(true);
        try {
            const page = await getWorkflowRuns(workflowId, 50, nextCursor, { headers: workspaceHeaders });
            setRuns((current) => [...current, ...page.items]);
            setNextCursor(page.nextCursor);
        } catch {
            toastError(t("runs.moreFailed"));
        } finally {
            setMoreLoading(false);
        }
    };

    const targetLabel = (run: WorkflowRunSummary) => {
        if (!run.trigger || run.trigger.recordId == null) return t("runs.scheduledTarget");
        const type = run.trigger.recordType;
        const record = type != null && RECORD_TYPES.includes(type)
            ? tr(`record.${type}`)
            : t("runs.recordFallback");
        return t("runs.target", { record, id: run.trigger.recordId });
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent
                size="xl"
                showCloseButton={false}
                className="max-h-[85dvh] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0"
            >
                <DialogHeader className="border-b border-border px-6 py-5">
                    <DialogTitle>{selected ? t("runs.detailTitle") : t("runs.title")}</DialogTitle>
                    <DialogDescription>
                        {selected
                            ? t("runs.detailDescription", { run: workflowRunNumber(selected.runKey) })
                            : t("runs.description", { name: workflowName })}
                    </DialogDescription>
                </DialogHeader>

                <div className="min-h-0 overflow-y-auto" aria-busy={loadState === "loading" || detailLoading}>
                    {selected ? (
                        <RunDetail run={selected} locale={locale} onOverlay={onSelectRun} />
                    ) : loadState === "loading" ? (
                        <RunsSkeleton />
                    ) : loadState === "error" ? (
                        <RunMessage
                            title={t("runs.errorTitle")}
                            body={t("runs.errorBody")}
                            action={(
                                <Button variant="outline" size="sm" onClick={() => setAttempt((current) => current + 1)}>
                                    {t("runs.retry")}
                                </Button>
                            )}
                        />
                    ) : runs.length === 0 ? (
                        <RunMessage title={t("runs.emptyTitle")} body={t("runs.emptyBody")} />
                    ) : (
                        <ol className="divide-y divide-border">
                            {runs.map((run) => (
                                <li key={run.runKey} className="space-y-3 px-6 py-4">
                                    <div className="flex flex-wrap items-center justify-between gap-2">
                                        <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[normalizeWorkflowRunStatus(run.status)]}>
                                            <WorkflowRunStatusIcon status={normalizeWorkflowRunStatus(run.status)} className="size-3" />
                                            {run.status === "partial"
                                                ? t("runs.legacyPartial")
                                                : t(`runs.status.${normalizeWorkflowRunStatus(run.status)}`)}
                                        </Badge>
                                        <time
                                            className="text-xs text-muted-foreground"
                                            dateTime={normalizeWorkflowRunDateTime(run.startedAt)}
                                        >
                                            {formatWorkflowRunDateTime(run.startedAt, locale)}
                                        </time>
                                    </div>
                                    <dl className="grid gap-2 text-xs sm:grid-cols-3">
                                        <div className="min-w-0 space-y-1">
                                            <dt className="text-muted-foreground">{t("runs.targetLabel")}</dt>
                                            <dd className="font-medium text-foreground">{targetLabel(run)}</dd>
                                        </div>
                                        <div className="min-w-0 space-y-1">
                                            <dt className="text-muted-foreground">{t("runs.idLabel")}</dt>
                                            <dd className="-ml-2 text-foreground"><WorkflowRunReference runKey={run.runKey} /></dd>
                                        </div>
                                        <div className="min-w-0 space-y-1">
                                            <dt className="text-muted-foreground">{t("runs.versionLabel")}</dt>
                                            <dd className="font-medium text-foreground">
                                                {run.version ? t("versionShort", { number: run.version.number }) : t("runs.legacyVersion")}
                                            </dd>
                                        </div>
                                    </dl>
                                    <Button
                                        variant="ghost"
                                        size="sm"
                                        disabled={!run.stepDetailAvailable || detailLoading}
                                        onClick={() => void loadDetail(run)}
                                    >
                                        {run.stepDetailAvailable ? t("runs.inspectPath") : t("runs.legacySummaryOnly")}
                                    </Button>
                                </li>
                            ))}
                            {nextCursor ? (
                                <li className="px-6 py-4">
                                    <Button variant="outline" size="sm" disabled={moreLoading} onClick={() => void loadMore()}>
                                        {t("runs.loadMore")}
                                    </Button>
                                </li>
                            ) : null}
                        </ol>
                    )}
                </div>

                <DialogFooter className="border-t border-border px-6 py-4">
                    {selected ? (
                        <Button variant="outline" onClick={() => setSelected(null)}>{t("runs.back")}</Button>
                    ) : null}
                    <DialogClose asChild>
                        <Button variant="outline">{t("runs.close")}</Button>
                    </DialogClose>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}

function RunDetail({
    run,
    locale,
    onOverlay,
}: {
    run: WorkflowRunDetail;
    locale: string;
    onOverlay?: (run: WorkflowRunDetail) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const normalizedStatus = normalizeWorkflowRunStatus(run.status);
    return (
        <div className="space-y-5 px-6 py-5">
            <div className="flex flex-wrap items-center gap-2">
                <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[normalizedStatus]}>
                    <WorkflowRunStatusIcon status={normalizedStatus} className="size-3" />
                    {run.status === "partial" ? t("runs.legacyPartial") : t(`runs.status.${normalizedStatus}`)}
                </Badge>
                {run.version ? <Badge variant="outline">{t("versionShort", { number: run.version.number })}</Badge> : null}
                <time className="text-xs text-muted-foreground" dateTime={normalizeWorkflowRunDateTime(run.startedAt)}>
                    {formatWorkflowRunDateTime(run.startedAt, locale)}
                </time>
                {onOverlay && run.version ? (
                    <Button className="ml-auto" variant="outline" size="sm" onClick={() => onOverlay(run)}>
                        {t("runs.showOverlay")}
                    </Button>
                ) : null}
            </div>
            <ol className="space-y-2" aria-label={t("runs.pathLabel")}>
                {run.path.map((step) => (
                    <li key={`${step.sequence}-${step.nodeId}`} className="rounded-xl border border-border bg-muted/25 p-3">
                        <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-medium text-foreground">{t(`nodeType.${step.nodeType.toLowerCase()}`)}</span>
                            <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[step.status]}>
                                <WorkflowRunStatusIcon status={step.status} className="size-3" />
                                {t(`runs.status.${step.status}`)}
                            </Badge>
                            {step.selectedOutcome ? <Badge variant="secondary">{t(`branch.${step.selectedOutcome}`)}</Badge> : null}
                            <span className="ml-auto text-xs text-muted-foreground">
                                {step.durationMs == null ? t("runs.durationPending") : t("runs.durationMs", { value: step.durationMs })}
                            </span>
                        </div>
                        {step.failure ? (
                            <p className="mt-2 text-xs text-destructive">{t("runs.failureAtNode")}</p>
                        ) : null}
                    </li>
                ))}
            </ol>
        </div>
    );
}

function RunMessage({ title, body, action }: { title: string; body: string; action?: React.ReactNode }) {
    return (
        <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
            <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                <ClockIcon className="size-5" />
            </span>
            <div className="space-y-1">
                <p className="text-sm font-medium text-foreground">{title}</p>
                <p className="text-sm text-muted-foreground">{body}</p>
            </div>
            {action}
        </div>
    );
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
                    <div className="grid gap-3 sm:grid-cols-3">
                        <Skeleton className="h-8 w-full" />
                        <Skeleton className="h-8 w-full" />
                        <Skeleton className="h-8 w-full" />
                    </div>
                </div>
            ))}
        </div>
    );
}
