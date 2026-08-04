"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import type { RecordSelectOption } from "@/app/components/records/RecordSelect";
import {
    connectWorkflowBranch,
    createEmptyWorkflowGraph,
    disconnectWorkflowBranch,
    ensureScheduleEnrollment,
    insertWorkflowNode,
    removeWorkflowNode,
} from "@/app/components/settings/workflows/workflowGraph";
import {
    createWorkflowEditorHistory,
    mergeWorkflowDocuments,
    workflowDocumentIsDirty,
    workflowEditorReducer,
    type WorkflowEditorDocument,
    type WorkflowMergeConflict,
} from "@/app/components/settings/workflows/workflowEditorReducer";
import {
    ApiError,
    createWorkflow,
    disableWorkflow,
    enableWorkflow,
    getWorkflowById,
    getWorkflowVersions,
    publishWorkflow,
    saveWorkflowDraft,
    search,
    simulateWorkflow,
    validateWorkflow,
} from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type {
    WorkflowDiagnostic,
    WorkflowDto,
    WorkflowEdgeOutcome,
    WorkflowNode,
    WorkflowNodeType,
    WorkflowRunDetail,
    WorkflowSimulation,
    WorkflowValidation,
    WorkflowVersion,
} from "@/app/lib/types";

type Inspection =
    | { kind: "version"; version: WorkflowVersion }
    | { kind: "run"; run: WorkflowRunDetail };

type ConflictState = {
    serverWorkflow: WorkflowDto;
    document: WorkflowEditorDocument;
    conflicts: WorkflowMergeConflict[];
};

type PendingState = "idle" | "loading";

function documentFromWorkflow(workflow: WorkflowDto): WorkflowEditorDocument {
    return {
        name: workflow.name,
        description: workflow.description,
        recordType: workflow.recordType,
        executionMode: workflow.executionMode,
        definition: workflow.definition,
        canvas: workflow.canvas,
    };
}

function documentFromVersion(version: WorkflowVersion): WorkflowEditorDocument {
    return {
        name: version.name,
        description: version.description,
        recordType: version.recordType,
        executionMode: version.executionMode,
        definition: version.definition,
        canvas: version.canvas,
    };
}

/** Owns canonical workflow API orchestration and one shared canvas/outline authoring history. */
export function useWorkflowEditor({
    workflowId,
    activeWorkspaceId,
    switching,
    canRunAsSystem,
}: {
    workflowId?: number;
    activeWorkspaceId: number | null;
    switching: boolean;
    canRunAsSystem: boolean;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const router = useRouter();
    const initialGraph = useMemo(() => createEmptyWorkflowGraph(), []);
    const initialDocument = useMemo<WorkflowEditorDocument>(() => ({
        name: "",
        description: null,
        recordType: "deal",
        executionMode: "user",
        definition: initialGraph.definition,
        canvas: initialGraph.canvas,
    }), [initialGraph]);
    const [history, dispatch] = useReducer(workflowEditorReducer, initialDocument, createWorkflowEditorHistory);
    const [workflow, setWorkflow] = useState<WorkflowDto | null>(null);
    const [loadedWorkspaceId, setLoadedWorkspaceId] = useState<number | null>(workflowId == null ? activeWorkspaceId : null);
    const [loadState, setLoadState] = useState<PendingState>(workflowId != null ? "loading" : "idle");
    const [missing, setMissing] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [accessDenied, setAccessDenied] = useState(false);
    const [busyAction, setBusyAction] = useState<string | null>(null);
    const [validation, setValidation] = useState<WorkflowValidation | null>(null);
    const [versions, setVersions] = useState<WorkflowVersion[]>([]);
    const [versionsLoadState, setVersionsLoadState] = useState<PendingState>("idle");
    const [simulation, setSimulation] = useState<WorkflowSimulation | null>(null);
    const [simulationLoadState, setSimulationLoadState] = useState<PendingState>("idle");
    const [simulationRecords, setSimulationRecords] = useState<RecordSelectOption[]>([]);
    const [conflict, setConflict] = useState<ConflictState | null>(null);
    const [conflictOpen, setConflictOpen] = useState(false);
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(initialDocument.definition.entryNodeId);
    const [focusFieldPath, setFocusFieldPath] = useState<string | null>(null);
    const [focusRequestId, setFocusRequestId] = useState(0);
    const [inspection, setInspection] = useState<Inspection | null>(null);
    const searchControllerRef = useRef<AbortController | null>(null);
    const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const scopeRef = useRef({ activeWorkspaceId, switching });

    useLayoutEffect(() => {
        scopeRef.current = { activeWorkspaceId, switching };
    }, [activeWorkspaceId, switching]);

    const isCurrentWorkspace = useCallback((workspaceId: number) => (
        scopeRef.current.activeWorkspaceId === workspaceId && !scopeRef.current.switching
    ), []);

    const loadVersions = useCallback(async (id: number, workspaceId: number) => {
        setVersionsLoadState("loading");
        try {
            const loaded = await getWorkflowVersions(id, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (isCurrentWorkspace(workspaceId)) setVersions(loaded);
        } catch {
            if (isCurrentWorkspace(workspaceId)) toastError(t("versions.loadFailed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setVersionsLoadState("idle");
        }
    }, [isCurrentWorkspace, t]);

    useEffect(() => {
        if (workflowId != null || activeWorkspaceId == null || switching) return;
        const workspaceId = activeWorkspaceId;
        void Promise.resolve().then(() => {
            if (!isCurrentWorkspace(workspaceId)) return;
            searchControllerRef.current?.abort();
            if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
            setWorkflow(null);
            setLoadedWorkspaceId(workspaceId);
            setLoadState("idle");
            setMissing(false);
            setLoadError(false);
            setAccessDenied(false);
            setBusyAction(null);
            setValidation(null);
            setVersions([]);
            setVersionsLoadState("idle");
            setSimulation(null);
            setSimulationLoadState("idle");
            setSimulationRecords([]);
            setConflict(null);
            setConflictOpen(false);
            setInspection(null);
            dispatch({ type: "initialize", document: initialDocument });
            setSelectedNodeId(initialDocument.definition.entryNodeId);
        });
    }, [activeWorkspaceId, initialDocument, isCurrentWorkspace, switching, workflowId]);

    useEffect(() => {
        if (!activeWorkspaceId || workflowId == null || switching) return;
        const workspaceId = activeWorkspaceId;
        const controller = new AbortController();
        let active = true;
        void (async () => {
            setLoadState("loading");
            setWorkflow(null);
            setVersions([]);
            setVersionsLoadState("idle");
            setValidation(null);
            setSimulation(null);
            setSimulationLoadState("idle");
            setSimulationRecords([]);
            setConflict(null);
            setConflictOpen(false);
            setInspection(null);
            setBusyAction(null);
            searchControllerRef.current?.abort();
            if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
            setMissing(false);
            setLoadError(false);
            setAccessDenied(false);
            try {
                const loaded = await getWorkflowById(workflowId, {
                    signal: controller.signal,
                    headers: { "X-Workspace-Id": String(workspaceId) },
                });
                if (!active || controller.signal.aborted || !isCurrentWorkspace(workspaceId)) return;
                const document = documentFromWorkflow(loaded);
                setWorkflow(loaded);
                setLoadedWorkspaceId(workspaceId);
                dispatch({ type: "initialize", document });
                setSelectedNodeId(document.definition.entryNodeId);
                if (loaded.activeVersionId != null) void loadVersions(loaded.id, workspaceId);
            } catch (error) {
                if (!active || controller.signal.aborted || !isCurrentWorkspace(workspaceId)) return;
                if (error instanceof ApiError && error.status === 403) setAccessDenied(true);
                else if (error instanceof ApiError && error.status === 404) setMissing(true);
                else setLoadError(true);
            } finally {
                if (active && !controller.signal.aborted && isCurrentWorkspace(workspaceId)) setLoadState("idle");
            }
        })();
        return () => {
            active = false;
            controller.abort();
        };
    }, [activeWorkspaceId, isCurrentWorkspace, loadAttempt, loadVersions, switching, workflowId]);

    useEffect(() => () => {
        searchControllerRef.current?.abort();
        if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    }, []);

    const dirty = workflowDocumentIsDirty(history);
    const scopeReady = activeWorkspaceId != null
        && loadedWorkspaceId === activeWorkspaceId
        && !switching;
    const permissionReadOnly = history.present.executionMode === "system" && !canRunAsSystem;
    const readOnly = !scopeReady || workflow?.archivedAt != null || inspection != null || permissionReadOnly;
    const displayDocument = inspection?.kind === "version"
        ? documentFromVersion(inspection.version)
        : inspection?.kind === "run" && inspection.run.version
            ? {
                name: history.present.name,
                description: history.present.description,
                recordType: history.present.recordType,
                executionMode: history.present.executionMode,
                definition: inspection.run.version.definition,
                canvas: inspection.run.version.canvas,
            }
            : history.present;
    const displayRun = inspection?.kind === "run" ? inspection.run : null;
    const activeVersionNumber = workflow?.activeVersionId == null
        ? null
        : versions.find((version) => version.id === workflow.activeVersionId)?.versionNumber ?? null;

    const updateDocument = useCallback((document: WorkflowEditorDocument, mode: "transient" | "commit" | "untracked") => {
        dispatch({ type: mode === "transient" ? "replace" : mode === "untracked" ? "untracked" : "commit", document });
        setValidation(null);
        setSimulation(null);
    }, []);

    const changeNode = useCallback((node: WorkflowNode, mode: "transient" | "commit") => {
        let document: WorkflowEditorDocument = {
            ...history.present,
            definition: {
                ...history.present.definition,
                nodes: history.present.definition.nodes.map((candidate) => candidate.id === node.id ? node : candidate),
            },
        };
        if (node.type === "TRIGGER" && node.config.type === "schedule") {
            const enrollment = ensureScheduleEnrollment(
                document.definition,
                document.canvas,
                document.recordType ?? "deal",
            );
            document = { ...document, definition: enrollment.definition, canvas: enrollment.canvas };
        }
        updateDocument(document, mode);
    }, [history.present, updateDocument]);

    const changeMetadata = useCallback((
        field: "description" | "recordType" | "executionMode",
        value: string | null,
        mode: "transient" | "commit",
    ) => {
        let document = { ...history.present, [field]: value };
        if (field === "recordType" && typeof value === "string") {
            const trigger = document.definition.nodes.find((node) => node.id === document.definition.entryNodeId);
            if (trigger?.type === "TRIGGER" && trigger.config.type === "schedule") {
                const enrollment = ensureScheduleEnrollment(document.definition, document.canvas, value);
                document = { ...document, definition: enrollment.definition, canvas: enrollment.canvas };
            }
        }
        updateDocument(document, mode);
    }, [history.present, updateDocument]);

    const changeName = useCallback((name: string) => {
        updateDocument({ ...history.present, name }, "transient");
    }, [history.present, updateDocument]);

    const connectBranch = useCallback((sourceNodeId: string, outcome: WorkflowEdgeOutcome, targetNodeId: string) => {
        const definition = connectWorkflowBranch(history.present.definition, sourceNodeId, outcome, targetNodeId);
        if (definition !== history.present.definition) updateDocument({ ...history.present, definition }, "commit");
    }, [history.present, updateDocument]);

    const disconnectBranch = useCallback((sourceNodeId: string, outcome: WorkflowEdgeOutcome) => {
        const definition = disconnectWorkflowBranch(history.present.definition, sourceNodeId, outcome);
        updateDocument({ ...history.present, definition }, "commit");
    }, [history.present, updateDocument]);

    const insertNode = useCallback((
        sourceNodeId: string,
        outcome: WorkflowEdgeOutcome,
        type: Exclude<WorkflowNodeType, "TRIGGER">,
        position?: { x: number; y: number },
    ) => {
        const inserted = insertWorkflowNode(
            history.present.definition,
            history.present.canvas,
            sourceNodeId,
            outcome,
            type,
            history.present.recordType ?? "deal",
            position,
        );
        if (!inserted) {
            toastError(t("graphLimitReached"));
            return;
        }
        updateDocument({ ...history.present, definition: inserted.definition, canvas: inserted.canvas }, "commit");
        setSelectedNodeId(inserted.insertedNodeId);
        setFocusRequestId((current) => current + 1);
    }, [history.present, t, updateDocument]);

    const deleteNode = useCallback((nodeId: string) => {
        const removed = removeWorkflowNode(history.present.definition, history.present.canvas, nodeId);
        updateDocument({ ...history.present, definition: removed.definition, canvas: removed.canvas }, "commit");
        if (selectedNodeId === nodeId) setSelectedNodeId(history.present.definition.entryNodeId);
    }, [history.present, selectedNodeId, updateDocument]);

    const moveNode = useCallback((nodeId: string, position: { x: number; y: number }) => {
        updateDocument({
            ...history.present,
            canvas: {
                ...history.present.canvas,
                positions: { ...history.present.canvas.positions, [nodeId]: position },
            },
        }, "commit");
    }, [history.present, updateDocument]);

    const moveViewport = useCallback((viewport: { x: number; y: number; zoom: number }) => {
        updateDocument({ ...history.present, canvas: { ...history.present.canvas, viewport } }, "untracked");
    }, [history.present, updateDocument]);

    const reconcileServerWorkflow = useCallback((serverWorkflow: WorkflowDto) => {
        const serverDocument = documentFromWorkflow(serverWorkflow);
        const merged = mergeWorkflowDocuments(history.baseline, history.present, serverDocument);
        setWorkflow(serverWorkflow);
        setValidation(null);
        if (merged.conflicts.length === 0) {
            setConflict(null);
            setConflictOpen(false);
            dispatch({ type: "rebase", baseline: serverDocument, document: merged.document });
            toastSuccess(t("conflict.autoMerged"));
        } else {
            setConflict({ serverWorkflow, document: merged.document, conflicts: merged.conflicts });
            setConflictOpen(true);
        }
    }, [history.baseline, history.present, t]);

    const beginConflictRecovery = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || workspaceId == null || !scopeReady) return;
        try {
            const serverWorkflow = await getWorkflowById(workflow.id, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (isCurrentWorkspace(workspaceId)) reconcileServerWorkflow(serverWorkflow);
        } catch {
            if (isCurrentWorkspace(workspaceId)) toastError(t("conflict.loadFailed"));
        }
    }, [activeWorkspaceId, isCurrentWorkspace, reconcileServerWorkflow, scopeReady, t, workflow]);

    const save = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (workspaceId == null || !scopeReady) return;
        if (!history.present.name.trim()) {
            toastError(t("nameRequired"));
            return;
        }
        if (conflict) {
            await beginConflictRecovery();
            return;
        }
        setBusyAction("save");
        try {
            if (!workflow) {
                const created = await createWorkflow({
                    ...history.present,
                    name: history.present.name.trim(),
                }, { headers: { "X-Workspace-Id": String(workspaceId) } });
                if (!isCurrentWorkspace(workspaceId)) return;
                const savedDocument = documentFromWorkflow(created);
                setWorkflow(created);
                dispatch({ type: "markSaved", document: savedDocument });
                router.replace(`/workflows/${created.id}`);
                toastSuccess(t("created"));
            } else {
                const saved = await saveWorkflowDraft(workflow.id, {
                    ...history.present,
                    name: history.present.name.trim(),
                    expectedRevision: workflow.draftRevision,
                }, { headers: { "X-Workspace-Id": String(workspaceId) } });
                if (!isCurrentWorkspace(workspaceId)) return;
                const savedDocument = documentFromWorkflow(saved);
                setWorkflow(saved);
                dispatch({ type: "markSaved", document: savedDocument });
                toastSuccess(t("updated"));
            }
        } catch (error) {
            if (!isCurrentWorkspace(workspaceId)) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("saveFailed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, conflict, history.present, isCurrentWorkspace, router, scopeReady, t, workflow]);

    const validate = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || workspaceId == null || !scopeReady) return;
        setBusyAction("validate");
        try {
            const result = await validateWorkflow(workflow.id, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (!isCurrentWorkspace(workspaceId)) return;
            if (result.draftRevision !== workflow.draftRevision) {
                await beginConflictRecovery();
                return;
            }
            setValidation(result);
            if (result.valid) toastSuccess(t("validationReady"));
        } catch {
            if (isCurrentWorkspace(workspaceId)) toastError(t("validationFailed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkspace, scopeReady, t, workflow]);

    const publish = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || validation?.draftRevision !== workflow.draftRevision || !validation.canPublish
            || workspaceId == null || !scopeReady) return;
        setBusyAction("publish");
        try {
            const published = await publishWorkflow(workflow.id, workflow.draftRevision, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (!isCurrentWorkspace(workspaceId)) return;
            setWorkflow(published);
            setValidation(null);
            await loadVersions(published.id, workspaceId);
            toastSuccess(t("published"));
        } catch (error) {
            if (!isCurrentWorkspace(workspaceId)) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("publishFailed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkspace, loadVersions, scopeReady, t, validation, workflow]);

    const toggleEnabled = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || workspaceId == null || !scopeReady) return;
        setBusyAction("toggle");
        try {
            const init = { headers: { "X-Workspace-Id": String(workspaceId) } };
            const updated = workflow.enabled
                ? await disableWorkflow(workflow.id, init)
                : await enableWorkflow(workflow.id, init);
            if (!isCurrentWorkspace(workspaceId)) return;
            if (updated.draftRevision === workflow.draftRevision) setWorkflow(updated);
            else reconcileServerWorkflow(updated);
            toastSuccess(t(updated.enabled ? "enabled" : "disabled"));
        } catch {
            if (isCurrentWorkspace(workspaceId)) toastError(t("lifecycleFailed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setBusyAction(null);
        }
    }, [activeWorkspaceId, isCurrentWorkspace, reconcileServerWorkflow, scopeReady, t, workflow]);

    const runSimulation = useCallback(async (recordId: number) => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || workspaceId == null || !scopeReady) return;
        setSimulationLoadState("loading");
        try {
            const result = await simulateWorkflow(workflow.id, workflow.draftRevision, recordId, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (isCurrentWorkspace(workspaceId)) setSimulation(result);
        } catch (error) {
            if (!isCurrentWorkspace(workspaceId)) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("simulation.failed"));
        } finally {
            if (isCurrentWorkspace(workspaceId)) setSimulationLoadState("idle");
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkspace, scopeReady, t, workflow]);

    const searchSimulationRecords = useCallback((query: string) => {
        searchControllerRef.current?.abort();
        if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
        if (query.trim().length < 2) {
            setSimulationRecords([]);
            return;
        }
        const workspaceId = activeWorkspaceId;
        if (workspaceId == null || !scopeReady) return;
        searchTimerRef.current = setTimeout(() => {
            const controller = new AbortController();
            searchControllerRef.current = controller;
            void search(query.trim(), {
                signal: controller.signal,
                headers: { "X-Workspace-Id": String(workspaceId) },
            })
                .then((results) => {
                    if (controller.signal.aborted || !isCurrentWorkspace(workspaceId)) return;
                    const recordType = history.present.recordType;
                    const options: RecordSelectOption[] = recordType === "company"
                        ? results.companies.map((company) => ({ id: company.id, label: company.name, imageUrl: company.logoUrl }))
                        : recordType === "person"
                            ? results.people.map((person) => ({ id: person.id, label: person.name, imageUrl: person.imageUrl }))
                            : recordType === "task"
                                ? results.tasks.map((task) => ({ id: task.id, label: task.description }))
                                : results.deals.map((deal) => ({ id: deal.id, label: deal.name }));
                    setSimulationRecords(options.slice(0, 50));
                })
                .catch(() => {
                    if (!controller.signal.aborted && isCurrentWorkspace(workspaceId)) setSimulationRecords([]);
                });
        }, 200);
    }, [activeWorkspaceId, history.present.recordType, isCurrentWorkspace, scopeReady]);

    const selectDiagnostic = useCallback((diagnostic: WorkflowDiagnostic) => {
        if (!diagnostic.nodeId) return;
        setInspection(null);
        setSelectedNodeId(diagnostic.nodeId);
        setFocusFieldPath(diagnostic.fieldPath);
        setFocusRequestId((current) => current + 1);
    }, []);

    const resolveConflict = useCallback((document: WorkflowEditorDocument) => {
        if (!conflict) return;
        const baseline = documentFromWorkflow(conflict.serverWorkflow);
        setWorkflow(conflict.serverWorkflow);
        dispatch({ type: "rebase", baseline, document });
        setConflict(null);
        setConflictOpen(false);
        setSelectedNodeId((current) => current && document.definition.nodes.some((node) => node.id === current)
            ? current
            : document.definition.entryNodeId);
    }, [conflict]);

    const inspectVersion = useCallback((version: WorkflowVersion) => {
        setInspection({ kind: "version", version });
        setSelectedNodeId(version.definition.entryNodeId);
    }, []);

    const inspectRun = useCallback((run: WorkflowRunDetail) => {
        if (!run.version) return;
        setInspection({ kind: "run", run });
        setSelectedNodeId(run.version.definition.entryNodeId);
    }, []);

    return {
        workflow,
        history,
        document: displayDocument,
        run: displayRun,
        inspection,
        loading: loadState === "loading" || !scopeReady,
        missing,
        loadError,
        accessDenied,
        busyAction,
        validation,
        versions: scopeReady ? versions : [],
        versionsLoading: scopeReady && versionsLoadState === "loading",
        simulation: scopeReady ? simulation : null,
        simulationLoading: scopeReady && simulationLoadState === "loading",
        simulationRecords: scopeReady ? simulationRecords : [],
        conflict,
        conflictOpen,
        selectedNodeId,
        focusFieldPath,
        focusRequestId,
        dirty,
        readOnly,
        activeVersionNumber,
        setSelectedNodeId,
        setFocusFieldPath,
        changeNode,
        changeMetadata,
        changeName,
        commitTransient: () => dispatch({ type: "commitTransient" }),
        undo: () => dispatch({ type: "undo" }),
        redo: () => dispatch({ type: "redo" }),
        connectBranch,
        disconnectBranch,
        insertNode,
        deleteNode,
        moveNode,
        moveViewport,
        save,
        validate,
        publish,
        toggleEnabled,
        runSimulation,
        clearSimulation: () => setSimulation(null),
        searchSimulationRecords,
        selectDiagnostic,
        resolveConflict,
        dismissConflict: () => setConflictOpen(false),
        loadVersions: workflow && activeWorkspaceId != null && scopeReady
            ? () => void loadVersions(workflow.id, activeWorkspaceId)
            : () => undefined,
        inspectVersion,
        inspectRun,
        exitInspection: () => {
            setInspection(null);
            setSelectedNodeId(history.present.definition.entryNodeId);
        },
        retryLoad: () => {
            setLoadState("loading");
            setLoadAttempt((current) => current + 1);
        },
    };
}
