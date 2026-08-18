"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
    ArrowPathIcon,
    BoltIcon,
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    PauseCircleIcon,
} from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import AccessDenied from "@/app/components/AccessDenied";
import { EmptyState } from "@/app/components/EmptyState";
import { PageHeader } from "@/app/components/PageHeader";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ApiError, getWorkflowOperationsRuns, getWorkflowOperationsSummary } from "@/app/lib/api";
import type {
    WorkflowOperationsRunItem,
    WorkflowOperationsSummary,
    WorkflowRunStatus,
    WorkflowTriggerDiagnostic,
} from "@/app/lib/types";
import {
    formatWorkflowRunDateTime,
    normalizeWorkflowRunDateTime,
    normalizeWorkflowRunStatus,
    WORKFLOW_RUN_STATUS_CLASS,
    WorkflowRunStatusIcon,
} from "@/app/components/settings/workflows/workflowRunStatus";
import { workflowRunNumber } from "@/app/components/settings/workflows/workflowRunKey";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

const RUN_STATUSES: WorkflowRunStatus[] = [
    "queued",
    "running",
    "waiting",
    "succeeded",
    "failed",
    "skipped",
    "cancelled",
    "intervention_required",
];
const FAILURE_CATEGORIES = ["actor", "permission", "reference", "retry", "configuration", "execution"] as const;

type OperationsData = {
    summary: WorkflowOperationsSummary;
    runs: WorkflowOperationsRunItem[];
    nextCursor: string | null;
};

/** Workspace-wide automation health and support-safe run triage. */
export default function WorkflowOperationsCenter() {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    const locale = useLocale();
    const { activeWorkspaceId, switching } = useWorkspace();
    const [data, setData] = useState<OperationsData | null>(null);
    const [status, setStatus] = useState("all");
    const [failureCategory, setFailureCategory] = useState("all");
    const [error, setError] = useState<"forbidden" | "load" | null>(null);
    const [paginationError, setPaginationError] = useState(false);
    const [attempt, setAttempt] = useState(0);
    const [moreLoading, setMoreLoading] = useState(false);

    const requestHeaders = useMemo(
        () => activeWorkspaceId == null ? undefined : { "X-Workspace-Id": String(activeWorkspaceId) },
        [activeWorkspaceId],
    );
    const filters = useMemo(() => ({
        status: status === "all" ? undefined : status,
        failureCategory: failureCategory === "all" ? undefined : failureCategory,
        limit: 50,
    }), [failureCategory, status]);

    useEffect(() => {
        if (!activeWorkspaceId || switching || !requestHeaders) return;
        const controller = new AbortController();
        void Promise.all([
            getWorkflowOperationsSummary({ signal: controller.signal, headers: requestHeaders }),
            getWorkflowOperationsRuns(filters, { signal: controller.signal, headers: requestHeaders }),
        ]).then(([summary, runs]) => {
            if (!controller.signal.aborted) {
                setError(null);
                setPaginationError(false);
                setData({ summary, runs: runs.items, nextCursor: runs.nextCursor });
            }
        }).catch((loadError: unknown) => {
            if (controller.signal.aborted) return;
            setError(loadError instanceof ApiError && loadError.status === 403 ? "forbidden" : "load");
        });
        return () => controller.abort();
    }, [activeWorkspaceId, attempt, filters, requestHeaders, switching]);

    const loadMore = useCallback(async () => {
        if (!data?.nextCursor || !requestHeaders || moreLoading) return;
        setMoreLoading(true);
        try {
            const page = await getWorkflowOperationsRuns(
                { ...filters, cursor: data.nextCursor },
                { headers: requestHeaders },
            );
            setData((current) => current ? {
                ...current,
                runs: [...current.runs, ...page.items],
                nextCursor: page.nextCursor,
            } : current);
            setPaginationError(false);
        } catch {
            setPaginationError(true);
        } finally {
            setMoreLoading(false);
        }
    }, [data, filters, moreLoading, requestHeaders]);

    if (error === "forbidden") {
        return <AccessDenied variant="page" title={t("access.title")} body={t("access.body")} />;
    }

    return (
        <>
            <PageHeader
                title={t("title")}
                description={t("description")}
                actions={(
                    <div className="flex flex-wrap items-center gap-2">
                        <Button asChild variant="outline">
                            <Link href="/workflows/recipes">{t("recipesAction")}</Link>
                        </Button>
                        <Button asChild variant="outline">
                            <Link href="/workflows">{t("workflowsAction")}</Link>
                        </Button>
                    </div>
                )}
            />

            {error === "load" && data === null ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-10 text-center">
                    <ExclamationTriangleIcon className="mx-auto size-7 text-destructive" />
                    <h2 className="mt-4 text-lg font-semibold text-foreground">{t("errors.title")}</h2>
                    <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t("errors.body")}</p>
                    <Button className="mt-5" variant="outline" onClick={() => setAttempt((value) => value + 1)}>
                        <ArrowPathIcon className="size-4" />
                        {t("retry")}
                    </Button>
                </div>
            ) : data === null || switching ? (
                <OperationsSkeleton />
            ) : (
                <div className="space-y-8">
                    {error === "load" || paginationError ? (
                        <Alert variant="destructive">
                            <ExclamationTriangleIcon />
                            <AlertTitle>{t("errors.title")}</AlertTitle>
                            <AlertDescription>{t("errors.body")}</AlertDescription>
                        </Alert>
                    ) : null}
                    <SummaryStrip summary={data.summary} />
                    {data.summary.triggerDiagnostics.length > 0 ? (
                        <TriggerDiagnostics
                            diagnostics={data.summary.triggerDiagnostics}
                            locale={locale}
                        />
                    ) : null}

                    <section className="space-y-4" aria-labelledby="operations-runs-heading">
                        <div className="flex flex-wrap items-end justify-between gap-3">
                            <div>
                                <h2 id="operations-runs-heading" className="text-lg font-semibold text-foreground">
                                    {t("runs.title")}
                                </h2>
                                <p className="text-sm text-muted-foreground">{t("runs.description")}</p>
                            </div>
                            <div className="flex flex-wrap gap-2">
                                <Select value={status} onValueChange={setStatus}>
                                    <SelectTrigger size="sm" aria-label={t("filters.statusLabel")}>
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="all">{t("filters.allStatuses")}</SelectItem>
                                        {RUN_STATUSES.map((value) => (
                                            <SelectItem key={value} value={value}>{tw(`runs.status.${value}`)}</SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                                <Select value={failureCategory} onValueChange={setFailureCategory}>
                                    <SelectTrigger size="sm" aria-label={t("filters.failureLabel")}>
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="all">{t("filters.allFailures")}</SelectItem>
                                        {FAILURE_CATEGORIES.map((value) => (
                                            <SelectItem key={value} value={value}>{t(`failureCategory.${value}`)}</SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        {data.runs.length === 0 ? (
                            <EmptyState
                                icon={ClockIcon}
                                title={t("runs.emptyTitle")}
                                body={t("runs.emptyBody")}
                                tone="muted"
                            />
                        ) : (
                            <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                {data.runs.map((item) => (
                                    <RunRow key={`${item.workflowId}:${item.run.runKey}`} item={item} locale={locale} />
                                ))}
                            </ol>
                        )}
                        {data.nextCursor ? (
                            <Button variant="outline" disabled={moreLoading} onClick={() => void loadMore()}>
                                {moreLoading ? t("runs.loadingMore") : t("runs.loadMore")}
                            </Button>
                        ) : null}
                    </section>
                </div>
            )}
        </>
    );
}

function TriggerDiagnostics({
    diagnostics,
    locale,
}: {
    diagnostics: WorkflowTriggerDiagnostic[];
    locale: string;
}) {
    const t = useTranslations("WorkflowOperations");
    return (
        <section className="space-y-3" aria-labelledby="trigger-diagnostics-heading">
            <div>
                <h2 id="trigger-diagnostics-heading" className="text-lg font-semibold text-foreground">
                    {t("triggerDiagnostics.title")}
                </h2>
                <p className="text-sm text-muted-foreground">{t("triggerDiagnostics.description")}</p>
            </div>
            <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-risk-high/40 bg-card">
                {diagnostics.map((diagnostic) => (
                    <li
                        key={diagnostic.outboxId}
                        className="grid gap-3 px-4 py-4 sm:grid-cols-[minmax(0,1fr)_auto]"
                    >
                        <div className="min-w-0 space-y-2">
                            <div className="flex flex-wrap items-center gap-2">
                                <ExclamationTriangleIcon className="size-4 shrink-0 text-risk-high" />
                                <Link
                                    href={`/workflows/${diagnostic.workflowId}`}
                                    className="truncate text-sm font-medium text-foreground underline-offset-4 hover:underline"
                                >
                                    {diagnostic.workflowName}
                                </Link>
                                <Badge variant="outline" className="border-risk-high/40 bg-risk-high/10 font-mono text-risk-high">
                                    {diagnostic.reasonCode}
                                </Badge>
                            </div>
                            <p className="text-xs text-muted-foreground">
                                {t("triggerDiagnostics.outcome", { triggerType: diagnostic.triggerType })}
                            </p>
                        </div>
                        <time
                            className="text-xs text-muted-foreground"
                            dateTime={normalizeWorkflowRunDateTime(diagnostic.failedAt)}
                        >
                            {formatWorkflowRunDateTime(diagnostic.failedAt, locale)}
                        </time>
                    </li>
                ))}
            </ol>
        </section>
    );
}

function SummaryStrip({ summary }: { summary: WorkflowOperationsSummary }) {
    const t = useTranslations("WorkflowOperations");
    const metrics = [
        { key: "workflows", value: summary.workflowCount, icon: BoltIcon },
        { key: "healthy", value: summary.healthyCount, icon: CheckCircleIcon },
        { key: "attention", value: summary.interventionRequiredCount, icon: ExclamationTriangleIcon },
        { key: "paused", value: summary.pausedCount, icon: PauseCircleIcon },
        { key: "queued", value: summary.queuedCount, icon: ClockIcon },
        { key: "overdue", value: summary.overdueCount, icon: ExclamationTriangleIcon },
    ] as const;
    return (
        <section aria-labelledby="operations-summary-heading" className="space-y-3">
            <h2 id="operations-summary-heading" className="text-sm font-semibold text-foreground">{t("summary.title")}</h2>
            <dl className="grid gap-px overflow-hidden rounded-2xl border border-border bg-border sm:grid-cols-3 xl:grid-cols-6">
                {metrics.map((metric) => {
                    const Icon = metric.icon;
                    return (
                        <div key={metric.key} className="bg-card px-4 py-4">
                            <dt className="flex items-center gap-2 text-xs text-muted-foreground">
                                <Icon className="size-4" />
                                {t(`summary.${metric.key}`)}
                            </dt>
                            <dd className="mt-2 text-2xl font-semibold tabular-nums text-foreground">{metric.value}</dd>
                        </div>
                    );
                })}
            </dl>
        </section>
    );
}

function RunRow({ item, locale }: { item: WorkflowOperationsRunItem; locale: string }) {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    const status = normalizeWorkflowRunStatus(item.run.status);
    return (
        <li>
            <Link
                href={`/workflows/${item.workflowId}/runs/${encodeURIComponent(item.run.runKey)}`}
                className="grid gap-3 px-4 py-4 transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring motion-reduce:transition-none sm:grid-cols-[minmax(0,1fr)_auto]"
            >
                <div className="min-w-0 space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                        <span className="truncate text-sm font-medium text-foreground">{item.workflowName}</span>
                        <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[status]}>
                            <WorkflowRunStatusIcon status={status} className="size-3" />
                            {item.run.status === "partial" ? tw("runs.legacyPartial") : tw(`runs.status.${status}`)}
                        </Badge>
                        {item.intervention ? (
                            <Badge variant="outline" className="border-risk-high/40 bg-risk-high/10 text-risk-high">
                                <ExclamationTriangleIcon className="size-3" />
                                {t("intervention.open")}
                            </Badge>
                        ) : null}
                        {item.recipeKey ? <Badge variant="outline">{t("runs.recipeOrigin")}</Badge> : null}
                    </div>
                    <p className="text-xs text-muted-foreground">
                        {t("runs.runMeta", {
                            run: workflowRunNumber(item.run.runKey),
                            version: item.run.version?.number ?? 0,
                        })}
                    </p>
                    {item.failureCategory ? (
                        <p className="text-xs text-muted-foreground">
                            {t("runs.failureCategory", { category: t(`failureCategory.${item.failureCategory}`) })}
                        </p>
                    ) : null}
                </div>
                <time
                    className="text-xs text-muted-foreground"
                    dateTime={normalizeWorkflowRunDateTime(item.run.startedAt)}
                >
                    {formatWorkflowRunDateTime(item.run.startedAt, locale)}
                </time>
            </Link>
        </li>
    );
}

function OperationsSkeleton() {
    return (
        <div className="space-y-8" aria-busy="true">
            <div className="grid gap-px overflow-hidden rounded-2xl border border-border bg-border sm:grid-cols-3 xl:grid-cols-6">
                {Array.from({ length: 6 }, (_, index) => (
                    <div key={index} className="space-y-3 bg-card p-4">
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-7 w-12" />
                    </div>
                ))}
            </div>
            <div className="space-y-3">
                <Skeleton className="h-6 w-40" />
                <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: 5 }, (_, index) => (
                        <div key={index} className="space-y-2 p-4">
                            <Skeleton className="h-4 w-48" />
                            <Skeleton className="h-3 w-72 max-w-full" />
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
