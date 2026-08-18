"use client";

import { useCallback, useEffect, useMemo, useReducer } from "react";
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import {
    ArrowPathIcon,
    BoltIcon,
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    PauseCircleIcon,
    PlayCircleIcon,
    StopCircleIcon,
    UserCircleIcon,
} from "@heroicons/react/24/outline";

import AccessDenied from "@/app/components/AccessDenied";
import { useActions } from "@/app/hooks/useActions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import {
    ApiError,
    assignWorkflowIntervention,
    cancelWorkflowRun,
    disableWorkflow,
    getUsers,
    getWorkflowOperations,
    getWorkflowRun,
    pauseWorkflow,
    resolveWorkflowIntervention,
    resumeWorkflow,
    retryWorkflowRun,
} from "@/app/lib/api";
import type {
    User,
    WorkflowIntervention,
    WorkflowOperationsDetail,
    WorkflowRunDetail,
} from "@/app/lib/types";
import { offeredWorkflowRetryStep } from "@/app/lib/workflowOperations";
import WorkflowRunReference from "@/app/components/settings/workflows/WorkflowRunReference";
import {
    formatWorkflowRunDateTime,
    normalizeWorkflowRunDateTime,
    normalizeWorkflowRunStatus,
    WORKFLOW_RUN_STATUS_CLASS,
    WorkflowRunStatusIcon,
} from "@/app/components/settings/workflows/workflowRunStatus";
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
import WorkflowRunDetailSkeleton from "@/app/components/settings/workflows/operations/WorkflowRunDetailSkeleton";

const ACTIVE_RUN_STATUSES = new Set(["queued", "running", "waiting"]);
const OPERATIONS_REASON_CODES = new Set([
    "actor_unavailable",
    "actor_inactive",
    "permission_denied",
    "action_permission_missing",
    "reference_unavailable",
    "record_unavailable",
    "retry_exhausted",
    "retry_not_safe",
    "execution_failed",
]);

type OperationsLoadError = "forbidden" | "missing" | "load" | null;

interface OperationsDetailState {
    run: WorkflowRunDetail | null;
    operations: WorkflowOperationsDetail | null;
    users: User[];
    error: OperationsLoadError;
    mutationError: boolean;
    pending: string | null;
    attempt: number;
}

type OperationsDetailAction =
    | {
        type: "load_succeeded";
        run: WorkflowRunDetail;
        operations: WorkflowOperationsDetail;
        users: User[];
    }
    | { type: "load_failed"; error: Exclude<OperationsLoadError, null> }
    | { type: "mutation_started"; key: string }
    | { type: "mutation_succeeded" }
    | { type: "mutation_failed" }
    | { type: "retry" };

const INITIAL_OPERATIONS_DETAIL_STATE: OperationsDetailState = {
    run: null,
    operations: null,
    users: [],
    error: null,
    mutationError: false,
    pending: null,
    attempt: 0,
};

function operationsDetailReducer(
    state: OperationsDetailState,
    action: OperationsDetailAction,
): OperationsDetailState {
    switch (action.type) {
        case "load_succeeded":
            return {
                ...state,
                run: action.run,
                operations: action.operations,
                users: action.users,
                error: null,
                mutationError: false,
            };
        case "load_failed":
            return { ...state, error: action.error };
        case "mutation_started":
            return { ...state, pending: action.key };
        case "mutation_succeeded":
            return {
                ...state,
                mutationError: false,
                pending: null,
                attempt: state.attempt + 1,
            };
        case "mutation_failed":
            return { ...state, mutationError: true, pending: null };
        case "retry":
            return { ...state, attempt: state.attempt + 1 };
    }
}

/** One run's recorded path, the workflow's operational state, and bounded intervention controls. */
export default function WorkflowRunOperationsDetail({ workflowId, runKey }: { workflowId: number; runKey: string }) {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    const locale = useLocale();
    const { openOverlay } = useActions();
    const { activeWorkspaceId, switching } = useWorkspace();
    const [state, dispatch] = useReducer(operationsDetailReducer, INITIAL_OPERATIONS_DETAIL_STATE);
    const { run, operations, users, error, mutationError, pending, attempt } = state;

    const headers = useMemo(
        () => activeWorkspaceId == null ? undefined : { "X-Workspace-Id": String(activeWorkspaceId) },
        [activeWorkspaceId],
    );

    useEffect(() => {
        if (!activeWorkspaceId || !headers || switching) return;
        const controller = new AbortController();
        void Promise.all([
            getWorkflowRun(workflowId, runKey, { signal: controller.signal, headers }),
            getWorkflowOperations(workflowId, { signal: controller.signal, headers }),
            getUsers({ signal: controller.signal, headers }).catch(() => []),
        ]).then(([loadedRun, loadedOperations, loadedUsers]) => {
            if (controller.signal.aborted) return;
            dispatch({
                type: "load_succeeded",
                run: loadedRun,
                operations: loadedOperations,
                users: loadedUsers,
            });
        }).catch((loadError: unknown) => {
            if (controller.signal.aborted) return;
            const errorType = loadError instanceof ApiError && loadError.status === 403
                ? "forbidden"
                : loadError instanceof ApiError && loadError.status === 404
                    ? "missing"
                    : "load";
            dispatch({ type: "load_failed", error: errorType });
        });
        return () => controller.abort();
    }, [activeWorkspaceId, attempt, headers, runKey, switching, workflowId]);

    const mutate = useCallback(async (key: string, operation: () => Promise<void>) => {
        dispatch({ type: "mutation_started", key });
        try {
            await operation();
            dispatch({ type: "mutation_succeeded" });
        } catch {
            dispatch({ type: "mutation_failed" });
        }
    }, []);

    if (error === "forbidden") {
        return <AccessDenied variant="page" title={t("access.title")} body={t("access.body")} />;
    }
    if (error === "missing") {
        return <EvidenceUnavailable title={t("detail.missingTitle")} body={t("detail.missingBody")} />;
    }
    if (error === "load") {
        return (
            <EvidenceUnavailable
                title={t("errors.title")}
                body={t("errors.body")}
                action={<Button variant="outline" onClick={() => dispatch({ type: "retry" })}>{t("retry")}</Button>}
            />
        );
    }
    if (!run || !operations || switching) return <WorkflowRunDetailSkeleton />;

    const normalizedStatus = normalizeWorkflowRunStatus(run.status);
    const retryStep = offeredWorkflowRetryStep(run);
    const intervention = operations.openInterventions.find((item) => item.runKey === run.runKey) ?? null;
    const canCancel = run.source === "canonical" && ACTIVE_RUN_STATUSES.has(run.status);
    const workflow = operations.workflow;

    return (
        <div className="space-y-8">
            <CrumbLabel pathname={`/workflows/${workflowId}`} value={workflow.name} />
            {mutationError ? (
                <Alert variant="destructive">
                    <ExclamationTriangleIcon />
                    <AlertTitle>{t("errors.mutationTitle")}</AlertTitle>
                    <AlertDescription>{t("errors.mutationBody")}</AlertDescription>
                </Alert>
            ) : null}
            <header className="space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-4">
                    <div className="min-w-0 space-y-2">
                        <div className="flex flex-wrap items-center gap-2">
                            <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[normalizedStatus]}>
                                <WorkflowRunStatusIcon status={normalizedStatus} className="size-3" />
                                {run.status === "partial" ? tw("runs.legacyPartial") : tw(`runs.status.${normalizedStatus}`)}
                            </Badge>
                            {run.version ? <Badge variant="outline">{tw("versionShort", { number: run.version.number })}</Badge> : null}
                            <WorkflowRunReference runKey={run.runKey} />
                        </div>
                        <h1 className="text-3xl font-bold tracking-tight text-foreground">{workflow.name}</h1>
                        <p className="text-sm text-muted-foreground">
                            {t("detail.started", { date: formatWorkflowRunDateTime(run.startedAt, locale) })}
                        </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                        {run.source === "canonical" && run.version ? (
                            <Button asChild variant="outline">
                                <Link href={`/workflows/${workflowId}?runKey=${encodeURIComponent(run.runKey)}`}>
                                    {t("detail.showOnWorkflow")}
                                </Link>
                            </Button>
                        ) : null}
                        <Button
                            variant="outline"
                            disabled={workflow.archivedAt !== null}
                            onClick={() => openOverlay({
                                kind: "workflow-manual-run",
                                sourceSurface: "record",
                                recordType: null,
                                scope: null,
                                workflowId,
                            })}
                        >
                            <PlayCircleIcon className="size-4" />
                            {t("detail.newRun")}
                        </Button>
                    </div>
                </div>
                <Alert>
                    <BoltIcon />
                    <AlertTitle>{t("detail.retryDistinctionTitle")}</AlertTitle>
                    <AlertDescription>{t("detail.retryDistinctionBody")}</AlertDescription>
                </Alert>
            </header>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1.5fr)_minmax(18rem,1fr)]">
                <main className="space-y-6">
                    <RunPath run={run} />
                    <DefinitionChanges operations={operations} locale={locale} />
                </main>
                <aside className="space-y-6">
                    <WorkflowHealth operations={operations} />
                    <Backlog operations={operations} locale={locale} />
                    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
                        <h2 className="text-sm font-semibold text-foreground">{t("controls.title")}</h2>
                        <div className="flex flex-wrap gap-2">
                            {workflow.intakePausedAt ? (
                                <Button
                                    size="sm"
                                    variant="outline"
                                    disabled={pending !== null || workflow.archivedAt !== null}
                                    onClick={() => void mutate("resume", async () => {
                                        await resumeWorkflow(workflowId, { headers });
                                    })}
                                >
                                    <PlayCircleIcon className="size-4" />
                                    {t("controls.resume")}
                                </Button>
                            ) : (
                                <Button
                                    size="sm"
                                    variant="outline"
                                    disabled={pending !== null || workflow.archivedAt !== null}
                                    onClick={() => void mutate("pause", async () => {
                                        await pauseWorkflow(workflowId, { headers });
                                    })}
                                >
                                    <PauseCircleIcon className="size-4" />
                                    {t("controls.pause")}
                                </Button>
                            )}
                            {workflow.enabled ? (
                                <Button
                                    size="sm"
                                    variant="outline"
                                    disabled={pending !== null || workflow.archivedAt !== null}
                                    onClick={() => void mutate("disable", async () => {
                                        await disableWorkflow(workflowId, { headers });
                                    })}
                                >
                                    <StopCircleIcon className="size-4" />
                                    {t("controls.disable")}
                                </Button>
                            ) : null}
                            {canCancel ? (
                                <Button
                                    size="sm"
                                    variant="outline"
                                    disabled={pending !== null || run.runtimeState?.cancellationRequested === true}
                                    onClick={() => void mutate("cancel", async () => {
                                        await cancelWorkflowRun(workflowId, run.runKey, { headers });
                                    })}
                                >
                                    {t(run.runtimeState?.cancellationRequested ? "controls.cancellationRequested" : "controls.cancelRun")}
                                </Button>
                            ) : null}
                            {retryStep ? (
                                <Button
                                    size="sm"
                                    variant="brand"
                                    disabled={pending !== null}
                                    onClick={() => void mutate("retry", async () => {
                                        await retryWorkflowRun(workflowId, run.runKey, { headers });
                                    })}
                                >
                                    <ArrowPathIcon className="size-4" />
                                    {t("controls.retryStep")}
                                </Button>
                            ) : null}
                        </div>
                        <p className="text-xs text-muted-foreground">{t("controls.pauseHelp")}</p>
                        <p className="text-xs text-muted-foreground">{t("controls.disableHelp")}</p>
                    </section>
                    {intervention ? (
                        <InterventionPanel
                            intervention={intervention}
                            users={users}
                            disabled={pending !== null}
                            onAssign={(ownerUserId) => void mutate("assign", async () => {
                                await assignWorkflowIntervention(
                                    intervention.id,
                                    ownerUserId,
                                    intervention.sourceVersion,
                                    { headers },
                                );
                            })}
                            onResolve={() => void mutate("resolve", async () => {
                                await resolveWorkflowIntervention(
                                    intervention.id,
                                    intervention.sourceVersion,
                                    { headers },
                                );
                            })}
                        />
                    ) : null}
                </aside>
            </div>
        </div>
    );
}

function RunPath({ run }: { run: WorkflowRunDetail }) {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    return (
        <section className="space-y-3" aria-labelledby="run-path-heading">
            <div>
                <h2 id="run-path-heading" className="text-lg font-semibold text-foreground">{t("path.title")}</h2>
                <p className="text-sm text-muted-foreground">{t("path.description")}</p>
            </div>
            {!run.stepDetailAvailable ? (
                <Alert>
                    <ClockIcon />
                    <AlertTitle>{t("path.legacyTitle")}</AlertTitle>
                    <AlertDescription>{t("path.legacyBody")}</AlertDescription>
                </Alert>
            ) : (
                <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {run.path.map((step) => {
                        const status = normalizeWorkflowRunStatus(step.status);
                        return (
                            <li key={`${step.sequence}:${step.nodeId}`} className="grid gap-3 px-4 py-4 sm:grid-cols-[2rem_minmax(0,1fr)_auto]">
                                <span className="flex size-7 items-center justify-center rounded-full border border-border text-xs tabular-nums text-muted-foreground">
                                    {step.sequence}
                                </span>
                                <div className="min-w-0 space-y-1">
                                    <p className="text-sm font-medium text-foreground">{t(`nodeType.${step.nodeType}`)}</p>
                                    <p className="font-mono text-xs text-muted-foreground">{step.nodeId}</p>
                                    {step.selectedOutcome ? (
                                        <p className="text-xs text-muted-foreground">{t("path.outcome", { outcome: tw(`branch.${step.selectedOutcome}`) })}</p>
                                    ) : null}
                                    {step.failure ? <FailureMessage code={step.failure.code} /> : null}
                                </div>
                                <div className="flex flex-col items-end gap-2">
                                    <Badge variant="outline" className={WORKFLOW_RUN_STATUS_CLASS[status]}>
                                        <WorkflowRunStatusIcon status={status} className="size-3" />
                                        {tw(`runs.status.${status}`)}
                                    </Badge>
                                    {step.nodeType === "action" ? (
                                        <span className="text-xs text-muted-foreground">
                                            {t("path.attempts", { count: step.attempts })} · {t(`retrySafety.${step.retrySafety}`)}
                                        </span>
                                    ) : null}
                                </div>
                            </li>
                        );
                    })}
                </ol>
            )}
        </section>
    );
}

function FailureMessage({ code }: { code: string }) {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    const message = OPERATIONS_REASON_CODES.has(code)
        ? t(`reason.${code}`)
        : tw.has(`diagnostics.${code}`)
            ? tw(`diagnostics.${code}`)
            : t("reason.unknown");
    return <p className="text-xs text-destructive">{message}</p>;
}

function WorkflowHealth({ operations }: { operations: WorkflowOperationsDetail }) {
    const t = useTranslations("WorkflowOperations");
    const state = operations.health.state;
    const icon = state === "healthy" ? CheckCircleIcon
        : state === "paused" ? PauseCircleIcon
            : state === "backlogged" ? ClockIcon
                : ExclamationTriangleIcon;
    const Icon = icon;
    return (
        <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
            <div className="flex items-center justify-between gap-3">
                <h2 className="text-sm font-semibold text-foreground">{t("health.title")}</h2>
                <Badge variant="outline">
                    <Icon className="size-3" />
                    {t(`health.state.${state}`)}
                </Badge>
            </div>
            <ul className="space-y-2 text-sm text-muted-foreground">
                {operations.health.signals.length === 0 ? <li>{t("health.noSignals")}</li> : operations.health.signals.map((signal) => (
                    <li key={signal} className="flex items-start gap-2">
                        <span className="mt-2 size-1.5 shrink-0 rounded-full bg-muted-foreground" />
                        {t(`health.signal.${signal}`)}
                    </li>
                ))}
            </ul>
            <dl className="border-t border-border pt-3 text-xs">
                <dt className="text-muted-foreground">{t("health.activeVersion")}</dt>
                <dd className="mt-1 font-medium text-foreground">
                    {operations.activeVersion ? t("health.version", { number: operations.activeVersion.number }) : t("health.none")}
                </dd>
            </dl>
        </section>
    );
}

function Backlog({ operations, locale }: { operations: WorkflowOperationsDetail; locale: string }) {
    const t = useTranslations("WorkflowOperations");
    const backlog = operations.backlog;
    return (
        <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
            <h2 className="text-sm font-semibold text-foreground">{t("backlog.title")}</h2>
            <dl className="grid grid-cols-2 gap-3 text-sm">
                <Metric label={t("backlog.queued")} value={backlog.queuedCount} />
                <Metric label={t("backlog.waiting")} value={backlog.waitingCount} />
                <Metric label={t("backlog.dueNow")} value={backlog.dueNowCount} />
                <Metric label={t("backlog.overdue")} value={backlog.overdueCount} />
            </dl>
            {backlog.nextResumeAt ? (
                <p className="text-xs text-muted-foreground">
                    {t("backlog.nextResume", { date: formatWorkflowRunDateTime(backlog.nextResumeAt, locale) })}
                </p>
            ) : null}
            {backlog.oldestQueuedAt ? (
                <p className="text-xs text-muted-foreground">
                    {t("backlog.oldestQueued", { date: formatWorkflowRunDateTime(backlog.oldestQueuedAt, locale) })}
                </p>
            ) : null}
            <p className="text-xs text-muted-foreground">
                {t("backlog.recentFailures", { count: backlog.recentFailureCount })}
            </p>
        </section>
    );
}

function Metric({ label, value }: { label: string; value: number }) {
    return (
        <div>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="mt-1 text-lg font-semibold tabular-nums text-foreground">{value}</dd>
        </div>
    );
}

function DefinitionChanges({ operations, locale }: { operations: WorkflowOperationsDetail; locale: string }) {
    const t = useTranslations("WorkflowOperations");
    return (
        <section className="space-y-3">
            <div>
                <h2 className="text-lg font-semibold text-foreground">{t("changes.title")}</h2>
                <p className="text-sm text-muted-foreground">{t("changes.description")}</p>
            </div>
            {operations.recentDefinitionChanges.length === 0 ? (
                <p className="rounded-2xl border border-border bg-card p-4 text-sm text-muted-foreground">{t("changes.empty")}</p>
            ) : (
                <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {operations.recentDefinitionChanges.map((change) => (
                        <li key={`${change.toVersion}:${change.publishedAt}`} className="space-y-2 px-4 py-4">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                                <p className="text-sm font-medium text-foreground">
                                    {change.fromVersion == null
                                        ? t("changes.initial", { version: change.toVersion })
                                        : t("changes.version", { from: change.fromVersion, to: change.toVersion })}
                                </p>
                                <time className="text-xs text-muted-foreground" dateTime={normalizeWorkflowRunDateTime(change.publishedAt)}>
                                    {formatWorkflowRunDateTime(change.publishedAt, locale)}
                                </time>
                            </div>
                            <p className="text-xs text-muted-foreground">
                                {t("changes.counts", {
                                    added: change.addedNodeIds.length,
                                    changed: change.changedNodeIds.length,
                                    removed: change.removedNodeIds.length,
                                })}
                            </p>
                        </li>
                    ))}
                </ol>
            )}
        </section>
    );
}

function InterventionPanel({
    intervention,
    users,
    disabled,
    onAssign,
    onResolve,
}: {
    intervention: WorkflowIntervention;
    users: User[];
    disabled: boolean;
    onAssign: (ownerUserId: number | null) => void;
    onResolve: () => void;
}) {
    const t = useTranslations("WorkflowOperations");
    return (
        <section className="space-y-4 rounded-2xl border border-risk-high/40 bg-risk-high/5 p-4">
            <div className="flex items-start gap-3">
                <ExclamationTriangleIcon className="mt-0.5 size-5 shrink-0 text-risk-high" />
                <div>
                    <h2 className="text-sm font-semibold text-foreground">{t("intervention.title")}</h2>
                    <p className="text-xs text-muted-foreground">{t(`failureCategory.${intervention.category}`)}</p>
                    <FailureMessage code={intervention.reasonCode} />
                </div>
            </div>
            <div className="space-y-2">
                <label htmlFor="intervention-owner" className="text-xs font-medium text-foreground">{t("intervention.owner")}</label>
                <Select
                    value={intervention.ownerUserId == null ? "unassigned" : String(intervention.ownerUserId)}
                    onValueChange={(value) => onAssign(value === "unassigned" ? null : Number(value))}
                    disabled={disabled}
                >
                    <SelectTrigger id="intervention-owner" className="w-full">
                        <UserCircleIcon className="size-4" />
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        <SelectItem value="unassigned">{t("intervention.unassigned")}</SelectItem>
                        {users.map((user) => <SelectItem key={user.id} value={String(user.id)}>{user.displayName}</SelectItem>)}
                    </SelectContent>
                </Select>
            </div>
            <Button size="sm" variant="outline" disabled={disabled} onClick={onResolve}>{t("intervention.resolve")}</Button>
        </section>
    );
}

function EvidenceUnavailable({ title, body, action }: { title: string; body: string; action?: React.ReactNode }) {
    return (
        <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center">
            <ExclamationTriangleIcon className="mx-auto size-7 text-muted-foreground" />
            <h1 className="mt-4 text-lg font-semibold text-foreground">{title}</h1>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{body}</p>
            {action ? <div className="mt-5 flex justify-center">{action}</div> : null}
        </div>
    );
}

