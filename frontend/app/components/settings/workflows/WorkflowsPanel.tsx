"use client";

import { useEffect, useLayoutEffect, useReducer, useRef } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import {
    ArchiveBoxIcon,
    ArrowUturnLeftIcon,
    BoltIcon,
    ClockIcon,
    DocumentDuplicateIcon,
    EllipsisHorizontalIcon,
    PencilSquareIcon,
    PauseCircleIcon,
    PlayCircleIcon,
    PlusIcon,
} from "@heroicons/react/24/outline";

import AccessDenied from "@/app/components/AccessDenied";
import ArchiveRecordDialog from "@/app/components/records/ArchiveRecordDialog";
import { EmptyState } from "@/app/components/EmptyState";
import { PageHeader } from "@/app/components/PageHeader";
import { useLiveNow } from "@/app/hooks/useNow";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import {
    ApiError,
    archiveWorkflow,
    disableWorkflow,
    enableWorkflow,
    getWorkflows,
    pauseWorkflow,
    restoreWorkflow,
    resumeWorkflow,
} from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { WorkflowListItem } from "@/app/lib/types";
import { formatRelativeTime } from "@/app/lib/utils";
import WorkflowRunsDialog from "@/app/components/settings/workflows/WorkflowRunsDialog";
import { useWorkflowDuplication } from "@/app/components/settings/workflows/useWorkflowDuplication";
import { useWorkflowWorkspaceAccess } from "@/app/components/settings/workflows/useWorkflowWorkspaceAccess";
import {
    formatWorkflowRunDateTime,
    normalizeWorkflowRunStatus,
    normalizeWorkflowRunDateTime,
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
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition-colors motion-reduce:transition-none hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

type WorkflowsPanelState = {
    archived: boolean;
    workflows: WorkflowListItem[];
    loadedWorkspaceId: number | null;
    loading: boolean;
    accessDenied: boolean;
    archiveTarget: WorkflowListItem | null;
    runsTarget: WorkflowListItem | null;
    pendingId: number | null;
};

type WorkflowsPanelAction = Partial<WorkflowsPanelState>
    | ((state: WorkflowsPanelState) => Partial<WorkflowsPanelState>);

const INITIAL_WORKFLOWS_PANEL_STATE: WorkflowsPanelState = {
    archived: false,
    workflows: [],
    loadedWorkspaceId: null,
    loading: true,
    accessDenied: false,
    archiveTarget: null,
    runsTarget: null,
    pendingId: null,
};

function workflowsPanelReducer(state: WorkflowsPanelState, action: WorkflowsPanelAction): WorkflowsPanelState {
    const patch = typeof action === "function" ? action(state) : action;
    return { ...state, ...patch };
}

/** The workflow list, with lifecycle, archive, duplication, and each workflow's latest run. */
export default function WorkflowsPanel() {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkflowAuthoring");
    const locale = useLocale();
    const now = useLiveNow();
    const router = useRouter();
    const { activeWorkspaceId, switching } = useWorkspace();
    const { canRunAsSystem } = useWorkflowWorkspaceAccess();
    const { duplicateWorkflow, duplicatingWorkflowId } = useWorkflowDuplication({
        activeWorkspaceId,
        canRunAsSystem,
        switching,
    });
    const [{
        archived,
        workflows,
        loadedWorkspaceId,
        loading,
        accessDenied,
        archiveTarget,
        runsTarget,
        pendingId,
    }, updatePanelState] = useReducer(workflowsPanelReducer, INITIAL_WORKFLOWS_PANEL_STATE);
    const scopeRef = useRef({ activeWorkspaceId, switching });
    const lifetimeRef = useRef(false);

    useLayoutEffect(() => {
        scopeRef.current = { activeWorkspaceId, switching };
    }, [activeWorkspaceId, switching]);

    useEffect(() => {
        lifetimeRef.current = true;
        return () => {
            lifetimeRef.current = false;
        };
    }, []);

    const isPanelActive = (workspaceId: number) => (
        lifetimeRef.current
        && scopeRef.current.activeWorkspaceId === workspaceId
        && !scopeRef.current.switching
    );

    useEffect(() => {
        if (!activeWorkspaceId || switching) return;
        const workspaceId = activeWorkspaceId;
        const controller = new AbortController();
        let active = true;
        void (async () => {
            updatePanelState({ loading: true, accessDenied: false });
            try {
                const loaded = await getWorkflows(archived, {
                    signal: controller.signal,
                    headers: { "X-Workspace-Id": String(workspaceId) },
                });
                if (active && !controller.signal.aborted && scopeRef.current.activeWorkspaceId === workspaceId) {
                    updatePanelState({
                        workflows: loaded,
                        loadedWorkspaceId: workspaceId,
                        archiveTarget: null,
                        runsTarget: null,
                        pendingId: null,
                    });
                }
            } catch (error) {
                if (!active || controller.signal.aborted) return;
                if (error instanceof ApiError && error.status === 403) updatePanelState({ accessDenied: true });
                else toastError(t("loadFailed"), { description: t("loadFailedBody") });
            } finally {
                if (active && !controller.signal.aborted && scopeRef.current.activeWorkspaceId === workspaceId) {
                    updatePanelState({ loading: false });
                }
            }
        })();
        return () => {
            active = false;
            controller.abort();
        };
    }, [activeWorkspaceId, archived, switching, t]);

    const visibleWorkflows = loadedWorkspaceId === activeWorkspaceId && !switching ? workflows : [];

    const toggleEnabled = async (workflow: WorkflowListItem) => {
        const workspaceId = activeWorkspaceId;
        if (workflow.archivedAt || workspaceId == null || switching) return;
        const nextEnabled = !workflow.enabled;
        updatePanelState((current) => ({
            pendingId: workflow.id,
            workflows: current.workflows.map((item) => (
                item.id === workflow.id ? { ...item, enabled: nextEnabled } : item
            )),
        }));
        try {
            const init = { headers: { "X-Workspace-Id": String(workspaceId) } };
            if (nextEnabled) await enableWorkflow(workflow.id, init);
            else await disableWorkflow(workflow.id, init);
            if (!isPanelActive(workspaceId)) return;
            toastSuccess(t(nextEnabled ? "enabled" : "disabled"));
        } catch {
            if (!isPanelActive(workspaceId)) return;
            updatePanelState((current) => ({
                workflows: current.workflows.map((item) => (
                    item.id === workflow.id ? { ...item, enabled: workflow.enabled } : item
                )),
            }));
            toastError(t("lifecycleFailed"), { description: t("lifecycleFailedBody") });
        } finally {
            if (isPanelActive(workspaceId)) updatePanelState({ pendingId: null });
        }
    };

    const confirmArchiveChange = async () => {
        const workspaceId = activeWorkspaceId;
        if (!archiveTarget || workspaceId == null || switching) return;
        updatePanelState({ pendingId: archiveTarget.id });
        try {
            if (archived) {
                await restoreWorkflow(archiveTarget.id, { headers: { "X-Workspace-Id": String(workspaceId) } });
                if (!isPanelActive(workspaceId)) return;
                toastSuccess(t("restored"));
            } else {
                await archiveWorkflow(archiveTarget.id, { headers: { "X-Workspace-Id": String(workspaceId) } });
                if (!isPanelActive(workspaceId)) return;
                toastSuccess(t("archived"));
            }
            updatePanelState((current) => ({
                workflows: current.workflows.filter((item) => item.id !== archiveTarget.id),
                archiveTarget: null,
            }));
        } catch {
            if (!isPanelActive(workspaceId)) return;
            toastError(t("lifecycleFailed"), { description: t("lifecycleFailedBody") });
        } finally {
            if (isPanelActive(workspaceId)) updatePanelState({ pendingId: null });
        }
    };

    const togglePaused = async (workflow: WorkflowListItem) => {
        const workspaceId = activeWorkspaceId;
        if (workflow.archivedAt || workspaceId == null || switching) return;
        updatePanelState({ pendingId: workflow.id });
        try {
            const init = { headers: { "X-Workspace-Id": String(workspaceId) } };
            const updated = workflow.intakePausedAt
                ? await resumeWorkflow(workflow.id, init)
                : await pauseWorkflow(workflow.id, init);
            if (!isPanelActive(workspaceId)) return;
            updatePanelState((current) => ({
                workflows: current.workflows.map((item) => item.id === workflow.id ? {
                    ...item,
                    intakePausedAt: updated.intakePausedAt,
                    intakePausedById: updated.intakePausedById,
                } : item),
            }));
            toastSuccess(t(workflow.intakePausedAt ? "resumed" : "paused"));
        } catch {
            if (!isPanelActive(workspaceId)) return;
            toastError(t("lifecycleFailed"), { description: t("lifecycleFailedBody") });
        } finally {
            if (isPanelActive(workspaceId)) updatePanelState({ pendingId: null });
        }
    };

    if (accessDenied) {
        return <AccessDenied variant="page" title={t("accessDeniedTitle")} body={tr("noAccess")} />;
    }

    return (
        <>
            <PageHeader
                title={t("title")}
                description={t("subtitle")}
                actions={(
                    <>
                        <Button onClick={() => router.push("/workflows/operations")} variant="outline" disabled={switching}>
                            {t("operations")}
                        </Button>
                        <Button onClick={() => router.push("/workflows/recipes")} variant="outline" disabled={switching}>
                            {t("recipes")}
                        </Button>
                        <Button onClick={() => router.push("/workflows/new")} variant="brand" disabled={switching}>
                            <PlusIcon className="size-4" />
                            {t("newWorkflow")}
                        </Button>
                    </>
                )}
            />

            <section className="space-y-4" aria-busy={loading}>
                <Tabs value={archived ? "archived" : "active"} onValueChange={(value) => updatePanelState({ archived: value === "archived" })}>
                    <TabsList aria-label={t("visibilityLabel")}>
                        <TabsTrigger value="active" disabled={switching}>{t("activeTab")}</TabsTrigger>
                        <TabsTrigger value="archived" disabled={switching}>{t("archivedTab")}</TabsTrigger>
                    </TabsList>
                </Tabs>

                {loading || switching ? (
                    <WorkflowSkeleton rows={3} />
                ) : visibleWorkflows.length === 0 ? (
                    <EmptyState
                        icon={archived ? ArchiveBoxIcon : BoltIcon}
                        title={t(archived ? "archivedEmptyTitle" : "emptyTitle")}
                        body={t(archived ? "archivedEmptyBody" : "emptyBody")}
                        tone={archived ? "muted" : "brand"}
                        action={!archived ? (
                            <Button onClick={() => router.push("/workflows/new")} variant="outline">
                                <PlusIcon className="size-4" />
                                {t("newWorkflow")}
                            </Button>
                        ) : undefined}
                    />
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {visibleWorkflows.map((workflow) => {
                            const busy = pendingId === workflow.id || duplicatingWorkflowId === workflow.id;
                            const latestStatus = workflow.latestRun
                                ? normalizeWorkflowRunStatus(workflow.latestRun.status)
                                : null;
                            return (
                                <li key={workflow.id} className="group flex items-center gap-3 px-4 py-3.5">
                                    <Switch
                                        checked={workflow.enabled}
                                        onCheckedChange={() => void toggleEnabled(workflow)}
                                        disabled={archived || busy || (workflow.executionMode === "system" && !canRunAsSystem)}
                                        aria-label={t("toggleEnabled", { name: workflow.name })}
                                    />
                                    <button
                                        type="button"
                                        onClick={() => router.push(`/workflows/${workflow.id}`)}
                                        disabled={busy}
                                        className="min-w-0 flex-1 space-y-1 rounded-md text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand disabled:cursor-wait disabled:opacity-60"
                                    >
                                        <span className="flex flex-wrap items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground group-hover:underline">
                                                {workflow.name}
                                            </span>
                                            <Badge variant="outline" className="text-muted-foreground">
                                                {workflow.activeVersion
                                                    ? t("versionShort", { number: workflow.activeVersion.number })
                                                    : t("draftOnly")}
                                            </Badge>
                                            {!workflow.enabled && !archived ? (
                                                <Badge variant="outline" className="text-muted-foreground">{t("disabledBadge")}</Badge>
                                            ) : null}
                                            {workflow.intakePausedAt && !archived ? (
                                                <Badge variant="outline" className="border-risk-low/40 bg-risk-low/10 text-foreground">
                                                    <PauseCircleIcon className="size-3" />
                                                    {t("pausedBadge")}
                                                </Badge>
                                            ) : null}
                                            {workflow.executionMode === "system" ? (
                                                <Badge variant="secondary" className="gap-1 text-muted-foreground">
                                                    <BoltIcon className="size-3" />
                                                    {tr("systemBadge")}
                                                </Badge>
                                            ) : null}
                                        </span>
                                        <span className="block truncate text-xs text-muted-foreground">
                                            {t("listSummary", {
                                                record: tr(`record.${workflow.recordType ?? "deal"}`),
                                                steps: workflow.nodeCount,
                                                actions: workflow.actionCount,
                                            })}
                                        </span>
                                        <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                                            {workflow.latestRun && latestStatus ? (
                                                <>
                                                    <Badge
                                                        variant="outline"
                                                        className={WORKFLOW_RUN_STATUS_CLASS[latestStatus]}
                                                    >
                                                        <WorkflowRunStatusIcon status={latestStatus} className="size-3" />
                                                        {workflow.latestRun.status === "partial"
                                                            ? t("runs.legacyPartial")
                                                            : t(`runs.status.${latestStatus}`)}
                                                    </Badge>
                                                    <time
                                                        dateTime={normalizeWorkflowRunDateTime(workflow.latestRun.startedAt)}
                                                        title={formatWorkflowRunDateTime(workflow.latestRun.startedAt, locale)}
                                                    >
                                                        {t("runs.latest", {
                                                            time: formatRelativeTime(workflow.latestRun.startedAt, locale, now),
                                                        })}
                                                    </time>
                                                </>
                                            ) : t("runs.emptyTitle")}
                                        </span>
                                    </button>
                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <button type="button" aria-label={t("rowActions", { name: workflow.name })} className={rowActionTrigger}>
                                                <EllipsisHorizontalIcon className="size-5" />
                                            </button>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end" className="w-44">
                                            <DropdownMenuItem disabled={busy} onSelect={() => router.push(`/workflows/${workflow.id}`)}>
                                                <PencilSquareIcon className="size-4" />
                                                {archived ? t("inspect") : tr("edit")}
                                            </DropdownMenuItem>
                                            {!archived ? (
                                                <DropdownMenuItem
                                                    disabled={busy || (workflow.executionMode === "system" && !canRunAsSystem)}
                                                    onSelect={() => void duplicateWorkflow(workflow)}
                                                >
                                                    <DocumentDuplicateIcon className="size-4" />
                                                    {duplicatingWorkflowId === workflow.id ? t("duplicating") : t("duplicate")}
                                                </DropdownMenuItem>
                                            ) : null}
                                            <DropdownMenuItem disabled={busy} onSelect={() => updatePanelState({ runsTarget: workflow })}>
                                                <ClockIcon className="size-4" />
                                                {t("runs.view")}
                                            </DropdownMenuItem>
                                            {!archived ? (
                                                <DropdownMenuItem disabled={busy} onSelect={() => void togglePaused(workflow)}>
                                                    {workflow.intakePausedAt
                                                        ? <PlayCircleIcon className="size-4" />
                                                        : <PauseCircleIcon className="size-4" />}
                                                    {t(workflow.intakePausedAt ? "resume" : "pause")}
                                                </DropdownMenuItem>
                                            ) : null}
                                            <DropdownMenuItem disabled={busy} onSelect={() => updatePanelState({ archiveTarget: workflow })}>
                                                {archived ? <ArrowUturnLeftIcon className="size-4" /> : <ArchiveBoxIcon className="size-4" />}
                                                {t(archived ? "restore" : "archive")}
                                            </DropdownMenuItem>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </section>

            {runsTarget && activeWorkspaceId && loadedWorkspaceId === activeWorkspaceId && !switching ? (
                <WorkflowRunsDialog
                    open
                    onOpenChange={(open) => {
                        if (!open) updatePanelState({ runsTarget: null });
                    }}
                    workflowId={runsTarget.id}
                    workflowName={runsTarget.name}
                    workspaceId={activeWorkspaceId}
                />
            ) : null}

            <ArchiveRecordDialog
                open={archiveTarget !== null && loadedWorkspaceId === activeWorkspaceId && !switching}
                onOpenChange={(open) => {
                    if (!open) updatePanelState({ archiveTarget: null });
                }}
                mode={archived ? "restore" : "archive"}
                selectedIds={new Set(archiveTarget ? [archiveTarget.id] : [])}
                selectedItems={archiveTarget ? [archiveTarget] : []}
                entityLabel={t("workflowEntityLabel")}
                entityLabelPlural={t("workflowEntityLabelPlural")}
                getDisplayName={(workflow) => workflow.name}
                isPending={archiveTarget ? pendingId === archiveTarget.id : false}
                onConfirm={() => void confirmArchiveChange()}
            />
        </>
    );
}

function WorkflowSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, index) => (
                <li key={index} className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="h-5 w-9 shrink-0 rounded-full" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-40" />
                        <Skeleton className="h-3 w-60 max-w-full" />
                        <div className="flex items-center gap-2">
                            <Skeleton className="h-5 w-20 rounded-full" />
                            <Skeleton className="h-3 w-24" />
                        </div>
                    </div>
                </li>
            ))}
        </ul>
    );
}
