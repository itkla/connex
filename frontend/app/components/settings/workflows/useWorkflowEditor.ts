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
    type WorkflowEditorAction,
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
    const [history, reduceHistory] = useReducer(workflowEditorReducer, initialDocument, createWorkflowEditorHistory);
    const historyRef = useRef(history);
    const documentGenerationRef = useRef(0);
    const dispatch = useCallback((action: WorkflowEditorAction) => {
        const currentHistory = historyRef.current;
        const nextHistory = workflowEditorReducer(currentHistory, action);
        const documentChanged = nextHistory.present !== currentHistory.present
            || nextHistory.baseline !== currentHistory.baseline;
        historyRef.current = nextHistory;
        if (documentChanged) documentGenerationRef.current += 1;
        reduceHistory(action);
        return documentChanged;
    }, []);
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
    const scopeRef = useRef({ activeWorkspaceId, switching, workflowId });
    const editorScopeGenerationRef = useRef(0);
    const versionsLoadGenerationRef = useRef(0);
    const conflictRecoveryGenerationRef = useRef(0);
    const simulationGenerationRef = useRef(0);
    const busyActionGenerationRef = useRef(0);
    const creationLockRef = useRef(false);
    const creationContinuationRef = useRef(0);

    useLayoutEffect(() => {
        scopeRef.current = { activeWorkspaceId, switching, workflowId };
        editorScopeGenerationRef.current += 1;
    }, [activeWorkspaceId, switching, workflowId]);

    useLayoutEffect(() => {
        historyRef.current = history;
    }, [history]);

    const isCurrentWorkspace = useCallback((workspaceId: number) => (
        scopeRef.current.activeWorkspaceId === workspaceId && !scopeRef.current.switching
    ), []);

    const isCurrentWorkflow = useCallback((id: number, workspaceId: number, scopeGeneration: number) => (
        editorScopeGenerationRef.current === scopeGeneration
        && scopeRef.current.workflowId === id
        && isCurrentWorkspace(workspaceId)
    ), [isCurrentWorkspace]);

    const clearSimulation = useCallback(() => {
        simulationGenerationRef.current += 1;
        setSimulation(null);
        setSimulationLoadState("idle");
    }, []);

    const invalidateDocumentEvidence = useCallback(() => {
        searchControllerRef.current?.abort();
        if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
        setValidation(null);
        clearSimulation();
        setSimulationRecords([]);
    }, [clearSimulation]);

    const loadVersions = useCallback(async (id: number, workspaceId: number, signal?: AbortSignal) => {
        if (signal?.aborted) return;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const loadGeneration = ++versionsLoadGenerationRef.current;
        setVersionsLoadState("loading");
        try {
            const loaded = await getWorkflowVersions(id, {
                signal,
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (!signal?.aborted
                && loadGeneration === versionsLoadGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setVersions(loaded);
        } catch {
            if (!signal?.aborted
                && loadGeneration === versionsLoadGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) toastError(t("versions.loadFailed"));
        } finally {
            if (!signal?.aborted
                && loadGeneration === versionsLoadGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setVersionsLoadState("idle");
        }
    }, [isCurrentWorkflow, t]);

    useEffect(() => {
        if (workflowId != null || activeWorkspaceId == null || switching) return;
        const workspaceId = activeWorkspaceId;
        let ignore = false;
        void Promise.resolve().then(() => {
            if (ignore || !isCurrentWorkspace(workspaceId)) return;
            conflictRecoveryGenerationRef.current += 1;
            versionsLoadGenerationRef.current += 1;
            busyActionGenerationRef.current += 1;
            setWorkflow(null);
            setLoadedWorkspaceId(workspaceId);
            setLoadState("idle");
            setMissing(false);
            setLoadError(false);
            setAccessDenied(false);
            setBusyAction(null);
            invalidateDocumentEvidence();
            setVersions([]);
            setVersionsLoadState("idle");
            setConflict(null);
            setConflictOpen(false);
            setInspection(null);
            dispatch({ type: "initialize", document: initialDocument });
            setSelectedNodeId(initialDocument.definition.entryNodeId);
        });
        return () => {
            ignore = true;
        };
    }, [activeWorkspaceId, dispatch, initialDocument, invalidateDocumentEvidence, isCurrentWorkspace, switching, workflowId]);

    useEffect(() => {
        if (!activeWorkspaceId || workflowId == null || switching) return;
        const workspaceId = activeWorkspaceId;
        const controller = new AbortController();
        let ignore = false;
        void (async () => {
            conflictRecoveryGenerationRef.current += 1;
            versionsLoadGenerationRef.current += 1;
            busyActionGenerationRef.current += 1;
            setLoadState("loading");
            setWorkflow(null);
            setVersions([]);
            setVersionsLoadState("idle");
            invalidateDocumentEvidence();
            setConflict(null);
            setConflictOpen(false);
            setInspection(null);
            setBusyAction(null);
            setMissing(false);
            setLoadError(false);
            setAccessDenied(false);
            try {
                const loaded = await getWorkflowById(workflowId, {
                    signal: controller.signal,
                    headers: { "X-Workspace-Id": String(workspaceId) },
                });
                if (ignore || loaded.id !== workflowId) return;
                if (controller.signal.aborted || !isCurrentWorkspace(workspaceId)) return;
                const document = documentFromWorkflow(loaded);
                setWorkflow(loaded);
                setLoadedWorkspaceId(workspaceId);
                dispatch({ type: "initialize", document });
                setSelectedNodeId(document.definition.entryNodeId);
                if (loaded.activeVersionId != null) void loadVersions(loaded.id, workspaceId, controller.signal);
            } catch (error) {
                if (ignore) return;
                if (controller.signal.aborted || !isCurrentWorkspace(workspaceId)) return;
                if (error instanceof ApiError && error.status === 403) setAccessDenied(true);
                else if (error instanceof ApiError && error.status === 404) setMissing(true);
                else setLoadError(true);
            } finally {
                if (!ignore && !controller.signal.aborted && isCurrentWorkspace(workspaceId)) setLoadState("idle");
            }
        })();
        return () => {
            ignore = true;
            controller.abort();
        };
    }, [activeWorkspaceId, dispatch, invalidateDocumentEvidence, isCurrentWorkspace, loadAttempt, loadVersions, switching, workflowId]);

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
    const creationLocked = workflowId == null && (busyAction === "save" || workflow != null);
    const editingReadOnly = readOnly || creationLocked;
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

    useLayoutEffect(() => {
        creationLockRef.current = creationLocked;
    }, [creationLocked]);

    const cancelCreationContinuation = useCallback(() => {
        creationContinuationRef.current += 1;
    }, []);

    useEffect(() => cancelCreationContinuation, [cancelCreationContinuation]);

    const updateDocument = useCallback((document: WorkflowEditorDocument, mode: "transient" | "commit" | "untracked") => {
        if (creationLockRef.current) return false;
        dispatch({ type: mode === "transient" ? "replace" : mode === "untracked" ? "untracked" : "commit", document });
        invalidateDocumentEvidence();
        return true;
    }, [dispatch, invalidateDocumentEvidence]);

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
        if (creationLockRef.current) return;
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
        if (!updateDocument({ ...history.present, definition: inserted.definition, canvas: inserted.canvas }, "commit")) return;
        setSelectedNodeId(inserted.insertedNodeId);
        setFocusRequestId((current) => current + 1);
    }, [history.present, t, updateDocument]);

    const deleteNode = useCallback((nodeId: string) => {
        const removed = removeWorkflowNode(history.present.definition, history.present.canvas, nodeId);
        if (!updateDocument({ ...history.present, definition: removed.definition, canvas: removed.canvas }, "commit")) return;
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
        const currentHistory = historyRef.current;
        const merged = mergeWorkflowDocuments(currentHistory.baseline, currentHistory.present, serverDocument);
        conflictRecoveryGenerationRef.current += 1;
        setWorkflow(serverWorkflow);
        invalidateDocumentEvidence();
        if (merged.conflicts.length === 0) {
            setConflict(null);
            setConflictOpen(false);
            dispatch({ type: "rebase", baseline: serverDocument, document: merged.document });
            toastSuccess(t("conflict.autoMerged"));
        } else {
            setConflict({ serverWorkflow, document: merged.document, conflicts: merged.conflicts });
            setConflictOpen(true);
        }
    }, [dispatch, invalidateDocumentEvidence, t]);

    const beginConflictRecovery = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const recoveryGeneration = ++conflictRecoveryGenerationRef.current;
        try {
            const serverWorkflow = await getWorkflowById(id, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (recoveryGeneration !== conflictRecoveryGenerationRef.current
                || serverWorkflow.id !== id
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            reconcileServerWorkflow(serverWorkflow);
        } catch {
            if (recoveryGeneration === conflictRecoveryGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) toastError(t("conflict.loadFailed"));
        }
    }, [activeWorkspaceId, isCurrentWorkflow, reconcileServerWorkflow, scopeReady, t, workflow]);

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
        const scopeGeneration = editorScopeGenerationRef.current;
        if (workflow
            ? !isCurrentWorkflow(workflow.id, workspaceId, scopeGeneration)
            : !isCurrentWorkspace(workspaceId)) return;
        const actionGeneration = ++busyActionGenerationRef.current;
        const creationContinuation = creationContinuationRef.current;
        let creationCompleted = false;
        if (!workflow) creationLockRef.current = true;
        setBusyAction("save");
        try {
            const submittedDocument = structuredClone(history.present);
            const requestDocument = {
                ...submittedDocument,
                name: submittedDocument.name.trim(),
            };
            if (!workflow) {
                const created = await createWorkflow(requestDocument, {
                    headers: { "X-Workspace-Id": String(workspaceId) },
                });
                creationCompleted = true;
                if (actionGeneration !== busyActionGenerationRef.current
                    || !isCurrentWorkspace(workspaceId)
                    || creationContinuation !== creationContinuationRef.current) return;
                const savedDocument = documentFromWorkflow(created);
                conflictRecoveryGenerationRef.current += 1;
                setWorkflow(created);
                dispatch({ type: "markSaved", submittedDocument, document: savedDocument });
                router.replace(`/workflows/${created.id}`);
                toastSuccess(t("created"));
            } else {
                const saved = await saveWorkflowDraft(workflow.id, {
                    ...requestDocument,
                    expectedRevision: workflow.draftRevision,
                }, { headers: { "X-Workspace-Id": String(workspaceId) } });
                if (saved.id !== workflow.id
                    || actionGeneration !== busyActionGenerationRef.current
                    || !isCurrentWorkflow(workflow.id, workspaceId, scopeGeneration)) return;
                const savedDocument = documentFromWorkflow(saved);
                conflictRecoveryGenerationRef.current += 1;
                setWorkflow(saved);
                dispatch({ type: "markSaved", submittedDocument, document: savedDocument });
                toastSuccess(t("updated"));
            }
        } catch (error) {
            const currentScope = workflow
                ? isCurrentWorkflow(workflow.id, workspaceId, scopeGeneration)
                : isCurrentWorkspace(workspaceId) && creationContinuation === creationContinuationRef.current;
            if (!currentScope || actionGeneration !== busyActionGenerationRef.current) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("saveFailed"));
        } finally {
            const creationContinuationCurrent = creationContinuation === creationContinuationRef.current;
            if (!workflow && !creationCompleted && creationContinuationCurrent) creationLockRef.current = false;
            const currentScope = workflow
                ? isCurrentWorkflow(workflow.id, workspaceId, scopeGeneration)
                : isCurrentWorkspace(workspaceId) && creationContinuationCurrent;
            if (currentScope && actionGeneration === busyActionGenerationRef.current) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, conflict, dispatch, history.present, isCurrentWorkflow, isCurrentWorkspace, router, scopeReady, t, workflow]);

    const validate = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const documentGeneration = documentGenerationRef.current;
        const actionGeneration = ++busyActionGenerationRef.current;
        setBusyAction("validate");
        try {
            const result = await validateWorkflow(id, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (documentGeneration !== documentGenerationRef.current
                || actionGeneration !== busyActionGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            if (result.draftRevision !== workflow.draftRevision) {
                await beginConflictRecovery();
                return;
            }
            setValidation(result);
            if (result.valid) toastSuccess(t("validationReady"));
        } catch {
            if (documentGeneration === documentGenerationRef.current
                && actionGeneration === busyActionGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) toastError(t("validationFailed"));
        } finally {
            if (actionGeneration === busyActionGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkflow, scopeReady, t, workflow]);

    const publish = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || validation?.draftRevision !== workflow.draftRevision || !validation.canPublish
            || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const actionGeneration = ++busyActionGenerationRef.current;
        setBusyAction("publish");
        try {
            const published = await publishWorkflow(id, workflow.draftRevision, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (published.id !== id
                || actionGeneration !== busyActionGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            conflictRecoveryGenerationRef.current += 1;
            setWorkflow(published);
            setValidation(null);
            await loadVersions(published.id, workspaceId);
            if (actionGeneration !== busyActionGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            toastSuccess(t("published"));
        } catch (error) {
            if (actionGeneration !== busyActionGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("publishFailed"));
        } finally {
            if (actionGeneration === busyActionGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setBusyAction(null);
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkflow, loadVersions, scopeReady, t, validation, workflow]);

    const toggleEnabled = useCallback(async () => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const actionGeneration = ++busyActionGenerationRef.current;
        setBusyAction("toggle");
        try {
            const init = { headers: { "X-Workspace-Id": String(workspaceId) } };
            const updated = workflow.enabled
                ? await disableWorkflow(id, init)
                : await enableWorkflow(id, init);
            if (updated.id !== id
                || actionGeneration !== busyActionGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            if (updated.draftRevision === workflow.draftRevision) {
                conflictRecoveryGenerationRef.current += 1;
                setWorkflow(updated);
            } else reconcileServerWorkflow(updated);
            toastSuccess(t(updated.enabled ? "enabled" : "disabled"));
        } catch {
            if (actionGeneration === busyActionGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) toastError(t("lifecycleFailed"));
        } finally {
            if (actionGeneration === busyActionGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setBusyAction(null);
        }
    }, [activeWorkspaceId, isCurrentWorkflow, reconcileServerWorkflow, scopeReady, t, workflow]);

    const runSimulation = useCallback(async (recordId: number) => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || dirty || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        const documentGeneration = documentGenerationRef.current;
        const simulationGeneration = ++simulationGenerationRef.current;
        setSimulationLoadState("loading");
        try {
            const result = await simulateWorkflow(id, workflow.draftRevision, recordId, {
                headers: { "X-Workspace-Id": String(workspaceId) },
            });
            if (documentGeneration === documentGenerationRef.current
                && simulationGeneration === simulationGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setSimulation(result);
        } catch (error) {
            if (documentGeneration !== documentGenerationRef.current
                || simulationGeneration !== simulationGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            if (error instanceof ApiError && error.status === 409) await beginConflictRecovery();
            else toastError(t("simulation.failed"));
        } finally {
            if (simulationGeneration === simulationGenerationRef.current
                && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setSimulationLoadState("idle");
        }
    }, [activeWorkspaceId, beginConflictRecovery, dirty, isCurrentWorkflow, scopeReady, t, workflow]);

    const searchSimulationRecords = useCallback((query: string) => {
        const workspaceId = activeWorkspaceId;
        if (!workflow || workspaceId == null || !scopeReady) return;
        const id = workflow.id;
        const scopeGeneration = editorScopeGenerationRef.current;
        if (!isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
        searchControllerRef.current?.abort();
        if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
        if (query.trim().length < 2) {
            setSimulationRecords([]);
            return;
        }
        const documentGeneration = documentGenerationRef.current;
        const recordType = history.present.recordType;
        searchTimerRef.current = setTimeout(() => {
            if (documentGeneration !== documentGenerationRef.current
                || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
            const controller = new AbortController();
            searchControllerRef.current = controller;
            void search(query.trim(), {
                signal: controller.signal,
                headers: { "X-Workspace-Id": String(workspaceId) },
            })
                .then((results) => {
                    if (controller.signal.aborted
                        || documentGeneration !== documentGenerationRef.current
                        || !isCurrentWorkflow(id, workspaceId, scopeGeneration)) return;
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
                    if (!controller.signal.aborted
                        && documentGeneration === documentGenerationRef.current
                        && isCurrentWorkflow(id, workspaceId, scopeGeneration)) setSimulationRecords([]);
                });
        }, 200);
    }, [activeWorkspaceId, history.present.recordType, isCurrentWorkflow, scopeReady, workflow]);

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
        conflictRecoveryGenerationRef.current += 1;
        setWorkflow(conflict.serverWorkflow);
        dispatch({ type: "rebase", baseline, document });
        invalidateDocumentEvidence();
        setConflict(null);
        setConflictOpen(false);
        setSelectedNodeId((current) => current && document.definition.nodes.some((node) => node.id === current)
            ? current
            : document.definition.entryNodeId);
    }, [conflict, dispatch, invalidateDocumentEvidence]);

    const inspectVersion = useCallback((version: WorkflowVersion) => {
        setInspection({ kind: "version", version });
        setSelectedNodeId(version.definition.entryNodeId);
    }, []);

    const inspectRun = useCallback((run: WorkflowRunDetail) => {
        if (!run.version) return;
        setInspection({ kind: "run", run });
        setSelectedNodeId(run.version.definition.entryNodeId);
    }, []);

    const commitTransient = useCallback(() => {
        if (!creationLockRef.current) dispatch({ type: "commitTransient" });
    }, [dispatch]);

    const undo = useCallback(() => {
        if (!creationLockRef.current && dispatch({ type: "undo" })) invalidateDocumentEvidence();
    }, [dispatch, invalidateDocumentEvidence]);

    const redo = useCallback(() => {
        if (!creationLockRef.current && dispatch({ type: "redo" })) invalidateDocumentEvidence();
    }, [dispatch, invalidateDocumentEvidence]);

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
        creationLocked,
        editingReadOnly,
        activeVersionNumber,
        setSelectedNodeId,
        setFocusFieldPath,
        changeNode,
        changeMetadata,
        changeName,
        commitTransient,
        undo,
        redo,
        connectBranch,
        disconnectBranch,
        insertNode,
        deleteNode,
        moveNode,
        moveViewport,
        cancelCreationContinuation,
        save,
        validate,
        publish,
        toggleEnabled,
        runSimulation,
        clearSimulation,
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
