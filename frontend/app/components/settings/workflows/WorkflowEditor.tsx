"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { ReactFlowProvider } from "@xyflow/react";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { ExclamationTriangleIcon, EyeIcon, XMarkIcon } from "@heroicons/react/24/outline";

import AccessDenied from "@/app/components/AccessDenied";
import WorkflowCanvasEditor from "@/app/components/settings/workflows/WorkflowCanvasEditor";
import WorkflowConflictDialog from "@/app/components/settings/workflows/WorkflowConflictDialog";
import WorkflowInspector from "@/app/components/settings/workflows/WorkflowInspector";
import WorkflowLifecycleBar from "@/app/components/settings/workflows/WorkflowLifecycleBar";
import WorkflowOutlineEditor from "@/app/components/settings/workflows/WorkflowOutlineEditor";
import WorkflowRunsDialog from "@/app/components/settings/workflows/WorkflowRunsDialog";
import WorkflowSimulationDialog from "@/app/components/settings/workflows/WorkflowSimulationDialog";
import WorkflowValidationSummary from "@/app/components/settings/workflows/WorkflowValidationSummary";
import WorkflowVersionsDialog from "@/app/components/settings/workflows/WorkflowVersionsDialog";
import { useWorkflowEditor } from "@/app/components/settings/workflows/useWorkflowEditor";
import { useWorkflowWorkspaceAccess } from "@/app/components/settings/workflows/useWorkflowWorkspaceAccess";
import { workflowDelayDiagnostics } from "@/app/components/settings/workflows/workflowGraph";
import { workflowRunReferenceParts } from "@/app/components/settings/workflows/workflowRunKey";
import { canAuthorTriggeredSend } from "@/app/components/settings/workflows/vocabulary";
import { useGrantedPermissions } from "@/app/hooks/usePermissions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import {
    getCampaignMessages,
    getCampaigns,
    getCompanies,
    getPipelines,
    getSegmentFields,
    getStagesByPipelineId,
    getWorkflowRun,
} from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import type {
    RuleBuilderOptions,
    SegmentFields,
    WorkflowCampaignMessageOptions,
    WorkflowDiagnostic,
    WorkflowDiagnosticCode,
    WorkflowEdgeOutcome,
} from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import {
    Drawer,
    DrawerClose,
    DrawerContent,
    DrawerDescription,
    DrawerHeader,
    DrawerTitle,
} from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

type AuthoringMode = "canvas" | "outline";
const NARROW_EDITOR_QUERY = "(max-width: 1023px)";

function subscribeToNarrowEditor(onChange: () => void): () => void {
    const media = globalThis.matchMedia(NARROW_EDITOR_QUERY);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
}

function narrowEditorSnapshot(): boolean {
    return globalThis.matchMedia(NARROW_EDITOR_QUERY).matches;
}

function narrowEditorServerSnapshot(): boolean {
    return false;
}

/** Full-height canonical workflow editor with peer canvas and outline authoring renderers. */
export default function WorkflowEditor({
    workflowId,
    triggeredSendEnabled,
}: {
    workflowId?: number;
    triggeredSendEnabled: boolean;
}) {
    return (
        <ReactFlowProvider>
            <WorkflowEditorBody
                workflowId={workflowId}
                triggeredSendEnabled={triggeredSendEnabled}
            />
        </ReactFlowProvider>
    );
}

function WorkflowEditorBody({
    workflowId,
    triggeredSendEnabled,
}: {
    workflowId?: number;
    triggeredSendEnabled: boolean;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkflowAuthoring");
    const router = useRouter();
    const searchParams = useSearchParams();
    const isNarrow = useSyncExternalStore(
        subscribeToNarrowEditor,
        narrowEditorSnapshot,
        narrowEditorServerSnapshot,
    );
    const { activeWorkspaceId, switching } = useWorkspace();
    const grantedPermissions = useGrantedPermissions();
    const { canRunAsSystem, members } = useWorkflowWorkspaceAccess();
    const editor = useWorkflowEditor({ workflowId, activeWorkspaceId, switching, canRunAsSystem });
    const loadedWorkflow = editor.workflow;
    const inspectRun = editor.inspectRun;
    const requestedRunKey = searchParams.get("runKey");
    const inspectedRunKeyRef = useRef<string | null>(null);
    const [mode, setMode] = useState<AuthoringMode>("canvas");
    const [inspectorOpen, setInspectorOpen] = useState(false);
    const [simulationOpen, setSimulationOpen] = useState(false);
    const [versionsOpen, setVersionsOpen] = useState(false);
    const [runsOpen, setRunsOpen] = useState(false);
    const [fieldsState, setFieldsState] = useState<{
        workspaceId: number;
        recordType: "company" | "person" | "deal";
        fields: SegmentFields;
    } | null>(null);
    const [referenceOptions, setReferenceOptions] = useState<{
        workspaceId: number;
        value: Omit<RuleBuilderOptions, "owners">;
    } | null>(null);
    const [campaignMessageOptions, setCampaignMessageOptions] =
        useState<(WorkflowCampaignMessageOptions & { workspaceId: number }) | null>(null);

    useEffect(() => {
        if (
            !requestedRunKey
            || !/^canonical-[1-9]\d*$/.test(requestedRunKey)
            || !activeWorkspaceId
            || !loadedWorkflow
            || inspectedRunKeyRef.current === requestedRunKey
        ) return;
        const controller = new AbortController();
        inspectedRunKeyRef.current = requestedRunKey;
        void getWorkflowRun(loadedWorkflow.id, requestedRunKey, {
            signal: controller.signal,
            headers: { "X-Workspace-Id": String(activeWorkspaceId) },
        }).then((run) => {
            if (!controller.signal.aborted) inspectRun(run);
        }).catch(() => {
            if (!controller.signal.aborted) toastError(t("runs.detailFailed"), { description: t("runs.detailFailedBody") });
        });
        return () => controller.abort();
    }, [activeWorkspaceId, inspectRun, loadedWorkflow, requestedRunKey, t]);

    useEffect(() => {
        if (!activeWorkspaceId) return;
        const workspaceHeaders = { "X-Workspace-Id": String(activeWorkspaceId) };
        const controller = new AbortController();
        let active = true;
        void Promise.all([
            getPipelines({ signal: controller.signal, headers: workspaceHeaders }).catch(() => []),
            getCompanies({ signal: controller.signal, headers: workspaceHeaders }).catch(() => []),
        ]).then(async ([pipelines, companies]) => {
            if (!active || controller.signal.aborted) return;
            const stageLists = await Promise.all(pipelines.map((pipeline) => (
                getStagesByPipelineId(pipeline.id, { signal: controller.signal, headers: workspaceHeaders })
                    .then((stages) => stages.map((stage) => ({ id: stage.id, name: stage.name, pipeline: pipeline.name })))
                    .catch(() => [])
            )));
            if (!active || controller.signal.aborted) return;
            setReferenceOptions({
                workspaceId: activeWorkspaceId,
                value: {
                    stages: stageLists.flat(),
                    companies: companies.map((company) => ({ id: company.id, name: company.name })),
                },
            });
        });
        return () => {
            active = false;
            controller.abort();
        };
    }, [activeWorkspaceId]);

    const options = useMemo<RuleBuilderOptions | null>(() => (
        referenceOptions?.workspaceId === activeWorkspaceId
            ? {
                ...referenceOptions.value,
                owners: members.map((member) => ({ id: member.id, name: member.displayName || member.username })),
            }
            : null
    ), [activeWorkspaceId, members, referenceOptions]);

    const canConfigureTriggeredSend = canAuthorTriggeredSend(
        editor.document.recordType,
        editor.document.executionMode,
        grantedPermissions,
        triggeredSendEnabled,
    );

    useEffect(() => {
        if (!activeWorkspaceId || !canConfigureTriggeredSend) return;
        const workspaceId = activeWorkspaceId;
        let active = true;
        const controller = new AbortController();
        const workspaceHeaders = { "X-Workspace-Id": String(workspaceId) };
        void getCampaigns({ signal: controller.signal, headers: workspaceHeaders })
            .then(async (campaigns) => {
                const messages = await Promise.all(campaigns.map(async (campaign) => ({
                    campaign,
                    messages: await getCampaignMessages(campaign.id, {
                        signal: controller.signal,
                        headers: workspaceHeaders,
                    }),
                })));
                if (!active || controller.signal.aborted) return;
                setCampaignMessageOptions({
                        workspaceId,
                        status: "ready",
                        items: messages.flatMap(({ campaign, messages: campaignMessages }) => (
                            campaignMessages.map((message) => ({ campaignName: campaign.name, message }))
                        )),
                    });
            })
            .catch(() => {
                if (!active || controller.signal.aborted) return;
                setCampaignMessageOptions({ workspaceId, status: "failed", items: [] });
            });
        return () => {
            active = false;
            controller.abort();
        };
    }, [activeWorkspaceId, canConfigureTriggeredSend]);

    const availableCampaignMessages = campaignMessageOptions?.workspaceId === activeWorkspaceId
        ? campaignMessageOptions
        : canConfigureTriggeredSend && activeWorkspaceId
            ? { status: "loading" as const, items: [] }
            : null;

    useEffect(() => {
        const recordType = editor.document.recordType;
        if ((recordType !== "company" && recordType !== "person" && recordType !== "deal") || activeWorkspaceId == null) return;
        const controller = new AbortController();
        void getSegmentFields(recordType, {
            signal: controller.signal,
            headers: { "X-Workspace-Id": String(activeWorkspaceId) },
        })
            .then((loaded) => {
                if (!controller.signal.aborted) {
                    setFieldsState({ workspaceId: activeWorkspaceId, recordType, fields: loaded });
                }
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    toastError(t("fieldsLoadFailed"), { description: t("fieldsLoadFailedBody") });
                }
            });
        return () => controller.abort();
    }, [activeWorkspaceId, editor.document.recordType, t]);

    const fields = fieldsState?.workspaceId === activeWorkspaceId
        && fieldsState.recordType === editor.document.recordType
        ? fieldsState.fields
        : null;

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if (editor.editingReadOnly) return;
            const target = event.target;
            if (target instanceof HTMLElement && (
                target.isContentEditable
                || target.tagName === "INPUT"
                || target.tagName === "TEXTAREA"
                || target.tagName === "SELECT"
            )) return;
            const modifier = event.metaKey || event.ctrlKey;
            if (!modifier || event.key.toLowerCase() !== "z") {
                if (event.ctrlKey && event.key.toLowerCase() === "y") {
                    event.preventDefault();
                    editor.redo();
                }
                return;
            }
            event.preventDefault();
            if (event.shiftKey) editor.redo();
            else editor.undo();
        };
        globalThis.addEventListener("keydown", onKeyDown);
        return () => globalThis.removeEventListener("keydown", onKeyDown);
    }, [editor]);

    const selectNode = useCallback((nodeId: string) => {
        editor.setSelectedNodeId(nodeId);
        editor.setFocusFieldPath(null);
        if (isNarrow) setInspectorOpen(true);
    }, [editor, isNarrow]);

    const nodeById = useMemo(
        () => new Map(editor.document.definition.nodes.map((node) => [node.id, node])),
        [editor.document.definition.nodes],
    );
    const nodeLabel = useCallback((nodeId: string) => {
        const node = nodeById.get(nodeId);
        if (!node) return t("unknownNode");
        if (node.type === "ACTION") return tr(`action.${node.config.type}`);
        return t(`nodeType.${node.type.toLowerCase()}`);
    }, [nodeById, t, tr]);
    const nodeSummary = useCallback((nodeId: string) => {
        const node = nodeById.get(nodeId);
        if (!node) return t("unknownNode");
        switch (node.type) {
            case "TRIGGER":
                return node.config.type === "schedule"
                    ? t("summary.schedule", { cadence: tr(`cadence.${node.config.cadence ?? "daily"}`) })
                    : node.config.events?.length
                        ? tr("summaryEntity", {
                            record: tr(`record.${editor.document.recordType ?? "deal"}`),
                            events: node.config.events.map((event) => tr(`event.${event}`)).join(", "),
                        })
                        : t("summary.anyChange");
            case "CONDITION": {
                const count = node.config.conditions.length + (node.config.groups?.length ?? 0);
                return count > 0 ? t("conditionSummarySet", { count }) : t("conditionSummaryEmpty");
            }
            case "ACTION":
                return node.config.title?.trim()
                    || node.config.body?.trim()
                    || node.config.activityType?.trim()
                    || tr(`action.${node.config.type}`);
            case "DELAY": {
                const seconds = node.config.durationSeconds;
                if (seconds > 0 && seconds % 86_400 === 0) return t("summary.delayDays", { value: seconds / 86_400 });
                if (seconds > 0 && seconds % 3_600 === 0) return t("summary.delayHours", { value: seconds / 3_600 });
                if (seconds > 0 && seconds % 60 === 0) return t("summary.delayMinutes", { value: seconds / 60 });
                return t("summary.delaySeconds", { value: seconds });
            }
            case "END":
                return t("summary.end");
        }
    }, [editor.document.recordType, nodeById, t, tr]);
    const branchLabel = useCallback((outcome: WorkflowEdgeOutcome) => t(`branch.${outcome}`), [t]);
    const diagnosticMessage = useCallback((diagnostic: {
        code: WorkflowDiagnosticCode;
        params: Record<string, string>;
    }) => t.has(`diagnostics.${diagnostic.code}`)
        ? t(`diagnostics.${diagnostic.code}`, diagnostic.params)
        : (
            <span title={diagnostic.code} aria-description={diagnostic.code}>
                {t("diagnosticFallback")}
            </span>
        ), [t]);
    const localDiagnostics = useMemo(
        () => workflowDelayDiagnostics(editor.document.definition),
        [editor.document.definition],
    );
    const visibleDiagnostics = useMemo(() => {
        const authoritative = editor.validation?.errors ?? [];
        const keys = new Set(localDiagnostics.map((diagnostic) => `${diagnostic.code}:${diagnostic.nodeId}:${diagnostic.fieldPath}`));
        return [
            ...localDiagnostics,
            ...authoritative.filter((diagnostic) => !keys.has(`${diagnostic.code}:${diagnostic.nodeId}:${diagnostic.fieldPath}`)),
        ];
    }, [editor.validation?.errors, localDiagnostics]);
    const visibleValidation = editor.validation
        ? {
            ...editor.validation,
            valid: editor.validation.valid && localDiagnostics.length === 0,
            canPublish: editor.validation.canPublish && localDiagnostics.length === 0,
            errors: visibleDiagnostics,
        }
        : localDiagnostics.length > 0
            ? {
                draftRevision: editor.workflow?.draftRevision ?? -1,
                valid: false,
                canPublish: false,
                systemAuthoringAllowed: canRunAsSystem,
                requiredPermissions: [],
                missingPermissions: [],
                errors: localDiagnostics,
            }
            : null;

    const rendererProps = {
        document: editor.document,
        selectedNodeId: editor.selectedNodeId,
        diagnostics: visibleDiagnostics,
        run: editor.run,
        readOnly: editor.editingReadOnly,
        focusNodeId: editor.selectedNodeId,
        focusRequestId: editor.focusRequestId,
        nodeLabel,
        nodeSummary,
        branchLabel,
        onSelectNode: selectNode,
        onConnectBranch: editor.connectBranch,
        onDisconnectBranch: editor.disconnectBranch,
        onInsertNode: editor.insertNode,
        onDeleteNode: editor.deleteNode,
    };

    const inspector = (
        <WorkflowInspector
            document={editor.document}
            selectedNodeId={editor.selectedNodeId}
            fields={fields}
            options={options}
            diagnostics={visibleDiagnostics}
            readOnly={editor.editingReadOnly}
            canRunAsSystem={canRunAsSystem}
            triggeredSendEnabled={triggeredSendEnabled}
            canConfigureTriggeredSend={canConfigureTriggeredSend}
            campaignMessageOptions={availableCampaignMessages}
            focusFieldPath={editor.focusFieldPath}
            focusRequestId={editor.focusRequestId}
            diagnosticMessage={(diagnostic) => diagnosticMessage(diagnostic)}
            onNodeChange={editor.changeNode}
            onMetadataChange={editor.changeMetadata}
            onCommitTransient={editor.commitTransient}
        />
    );

    if (editor.accessDenied) {
        return <AccessDenied variant="page" title={t("accessDeniedTitle")} body={tr("noAccess")} />;
    }
    if (editor.missing) {
        return (
            <div className="grid min-h-[calc(100dvh-4rem)] place-items-center bg-background px-4 text-center">
                <div>
                    <p className="text-sm text-muted-foreground">{t("notFound")}</p>
                    <Button variant="outline" size="sm" className="mt-3" onClick={() => router.push("/workflows")}>
                        {t("backToList")}
                    </Button>
                </div>
            </div>
        );
    }
    if (editor.loadError) {
        return (
            <div className="grid min-h-[calc(100dvh-4rem)] place-items-center bg-background px-4 text-center">
                <div className="max-w-sm">
                    <ExclamationTriangleIcon className="mx-auto size-8 text-destructive" />
                    <p className="mt-3 text-sm font-medium text-foreground">{t("editorLoadFailedTitle")}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{t("editorLoadFailedBody")}</p>
                    <Button variant="outline" size="sm" className="mt-4" onClick={editor.retryLoad}>
                        {t("retry")}
                    </Button>
                </div>
            </div>
        );
    }
    if (editor.loading || switching) return <EditorSkeleton />;

    return (
        <div className="flex min-h-[calc(100dvh-4rem)] flex-col bg-background">
            <WorkflowLifecycleBar
                name={editor.history.present.name}
                revision={editor.workflow?.draftRevision ?? -1}
                activeVersionNumber={editor.activeVersionNumber}
                hasActiveVersion={editor.workflow?.activeVersionId != null}
                enabled={editor.workflow?.enabled ?? false}
                executionMode={editor.history.present.executionMode}
                archived={editor.workflow?.archivedAt != null}
                dirty={editor.dirty}
                validation={editor.validation}
                canUndo={editor.history.past.length > 0 || editor.history.transientBase != null}
                canRedo={editor.history.future.length > 0}
                busyAction={editor.busyAction}
                readOnly={editor.editingReadOnly}
                onBack={() => {
                    editor.cancelCreationContinuation();
                    router.push("/workflows");
                }}
                onNameChange={editor.changeName}
                onNameCommit={editor.commitTransient}
                onUndo={editor.undo}
                onRedo={editor.redo}
                onSave={() => void editor.save()}
                onValidate={() => void editor.validate()}
                onPublish={() => void editor.publish()}
                onToggleEnabled={() => void editor.toggleEnabled()}
                onOpenSimulation={() => setSimulationOpen(true)}
                onOpenVersions={() => {
                    editor.loadVersions();
                    setVersionsOpen(true);
                }}
                onOpenRuns={() => setRunsOpen(true)}
            />

            {visibleValidation ? (
                <WorkflowValidationSummary
                    validation={visibleValidation}
                    diagnosticMessage={(diagnostic: WorkflowDiagnostic) => diagnosticMessage(diagnostic)}
                    onSelectDiagnostic={(diagnostic) => {
                        editor.selectDiagnostic(diagnostic);
                        if (isNarrow) setInspectorOpen(true);
                    }}
                />
            ) : null}

            {editor.inspection ? (
                <div className="flex flex-wrap items-center gap-2 border-b border-brand/30 bg-brand-light px-4 py-2 text-sm text-foreground">
                    <EyeIcon className="size-4" />
                    <span className="font-medium">
                        {editor.inspection.kind === "run"
                            ? t(
                                workflowRunReferenceParts(editor.inspection.run.runKey).earlier
                                    ? "inspection.legacyRun"
                                    : "inspection.run",
                                { run: workflowRunReferenceParts(editor.inspection.run.runKey).number },
                            )
                            : t("inspection.version", { number: editor.inspection.version.versionNumber })}
                    </span>
                    <span className="text-muted-foreground">{t("inspection.readOnly")}</span>
                    <Button variant="ghost" size="xs" className="ml-auto" onClick={editor.exitInspection}>
                        <XMarkIcon className="size-3.5" />
                        {t("inspection.exit")}
                    </Button>
                </div>
            ) : null}

            <div className="flex min-h-0 flex-1 flex-col">
                <div className="flex items-center gap-1 border-b border-border px-4 py-2">
                    {!isNarrow ? (
                        <div role="group" aria-label={t("authoringModeLabel")} className="flex rounded-full bg-muted p-0.5 ring-1 ring-border/60">
                            {(["canvas", "outline"] satisfies AuthoringMode[]).map((value) => (
                                <button
                                    key={value}
                                    type="button"
                                    aria-pressed={mode === value}
                                    onClick={() => setMode(value)}
                                    className={cn(
                                        "h-8 rounded-full px-3 text-xs font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                        mode === value ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
                                    )}
                                >
                                    {t(`authoringMode.${value}`)}
                                </button>
                            ))}
                        </div>
                    ) : (
                        <span className="text-sm font-medium text-foreground">{t("authoringMode.outline")}</span>
                    )}
                    {editor.readOnly && !editor.inspection ? (
                        <span className="ml-auto text-xs text-muted-foreground">
                            {editor.workflow?.archivedAt ? t("archivedReadOnly") : t("systemAuthoringRestricted")}
                        </span>
                    ) : null}
                </div>

                <div className="grid min-h-0 flex-1 lg:grid-cols-[minmax(0,1fr)_24rem]">
                    <section className="min-h-0 overflow-y-auto p-3 sm:p-4">
                        {!isNarrow && mode === "canvas" ? (
                            <WorkflowCanvasEditor
                                {...rendererProps}
                                runStatusLabel={(status) => t(`runs.status.${status}`)}
                                viewportLocked={editor.creationLocked}
                                onMoveNode={editor.moveNode}
                                onMoveViewport={editor.moveViewport}
                            />
                        ) : (
                            <WorkflowOutlineEditor {...rendererProps} />
                        )}
                    </section>
                    <aside className="hidden min-h-0 overflow-y-auto border-l border-border bg-card lg:block">
                        {!isNarrow ? inspector : null}
                    </aside>
                </div>
            </div>

            <Drawer
                open={isNarrow && inspectorOpen}
                onOpenChange={setInspectorOpen}
                swipeDirection="right"
                motionClassName="duration-200"
            >
                <DrawerContent showCloseButton={false} className="w-full gap-0 transition-transform duration-200 sm:max-w-sm">
                    <DrawerHeader className="border-b border-border">
                        <div className="flex items-center gap-2 pr-8">
                            <DrawerTitle>{t("inspectorTitle")}</DrawerTitle>
                            <DrawerClose
                                render={<Button className="ml-auto" variant="ghost" size="icon-sm" aria-label={t("close")} />}
                            >
                                <XMarkIcon className="size-4" />
                            </DrawerClose>
                        </div>
                        <DrawerDescription>{editor.selectedNodeId ? nodeLabel(editor.selectedNodeId) : t("selectNodePrompt")}</DrawerDescription>
                    </DrawerHeader>
                    <div className="min-h-0 flex-1 overflow-y-auto">{isNarrow ? inspector : null}</div>
                </DrawerContent>
            </Drawer>

            <WorkflowConflictDialog
                open={editor.conflictOpen}
                document={editor.conflict?.document ?? editor.history.present}
                conflicts={editor.conflict?.conflicts ?? []}
                onCancel={editor.dismissConflict}
                onResolve={editor.resolveConflict}
            />
            <WorkflowSimulationDialog
                open={simulationOpen}
                records={editor.simulationRecords}
                loading={editor.simulationLoading}
                supported={editor.simulationSupported}
                result={editor.simulation}
                diagnosticMessage={diagnosticMessage}
                onOpenChange={setSimulationOpen}
                onSearch={editor.searchSimulationRecords}
                onClear={editor.clearSimulation}
                onSimulate={(recordId) => void editor.runSimulation(recordId)}
            />
            <WorkflowVersionsDialog
                open={versionsOpen}
                versions={editor.versions}
                activeVersionId={editor.workflow?.activeVersionId ?? null}
                loading={editor.versionsLoading}
                onOpenChange={setVersionsOpen}
                onInspect={(version) => {
                    editor.inspectVersion(version);
                    setVersionsOpen(false);
                }}
            />
            {runsOpen && editor.workflow && activeWorkspaceId ? (
                <WorkflowRunsDialog
                    open
                    onOpenChange={setRunsOpen}
                    workflowId={editor.workflow.id}
                    workflowName={editor.workflow.name}
                    workspaceId={activeWorkspaceId}
                    onSelectRun={(run) => {
                        editor.inspectRun(run);
                        setRunsOpen(false);
                    }}
                />
            ) : null}
        </div>
    );
}

function EditorSkeleton() {
    return (
        <div className="flex min-h-[calc(100dvh-4rem)] flex-col bg-background">
            <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3">
                <Skeleton className="size-8 rounded-md" />
                <Skeleton className="h-9 w-64 max-w-full" />
                <Skeleton className="ml-auto h-9 w-32" />
            </div>
            <div className="grid min-h-0 flex-1 lg:grid-cols-[minmax(0,1fr)_24rem]">
                <Skeleton className="m-4 hidden rounded-2xl lg:block" />
                <Skeleton className="m-4 min-h-96 rounded-2xl" />
            </div>
        </div>
    );
}
