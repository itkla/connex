"use client";

import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useState, useSyncExternalStore } from "react";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import { ApiError, createRadarTask, getContactById, getContacts, getDealById, getDeals, getUsers } from "@/app/lib/api";
import type { Contact, CreateTaskPayload, Deal, User } from "@/app/lib/types";
import type { CreateDefaults, OverlayRequest } from "@/app/lib/actions/types";
import { ACTIVITY_TYPES } from "@/app/components/activity/activities/activityTypes";
import { publishRecordMutation } from "@/app/lib/record-mutation-events";
import { submitRadarTaskWithCurrentSignal, type RadarTaskSignalSnapshot } from "@/app/lib/radar";
import { toastError, toastWarn } from "@/app/lib/toast";
import { draftKey, getDraftKeyGeneration, subscribeDraftChanges } from "@/app/lib/formDrafts";
import ImportDialog from "@/app/components/import/LazyImportDialog";
import { OverlayChunkFailureBoundary } from "@/app/components/actions/OverlayChunkFailureBoundary";
import {
    OVERLAY_MAX_EXIT_DURATION_MS,
    reduceOverlayRetention,
    type OverlayLifecycleCapabilities,
} from "@/lib/overlay-lifecycle";

const TaskDialog = dynamic(() => import("@/app/components/activity/tasks/TaskDialog"));
const NoteDialog = dynamic(() => import("@/app/components/activity/notes/NoteDialog"));
const ActivityDialog = dynamic(() => import("@/app/components/activity/activities/ActivityDialog"));
const CompanyCreateContainer = dynamic(() => import("@/app/components/actions/create/CompanyCreateContainer"));
const ContactCreateContainer = dynamic(() => import("@/app/components/actions/create/ContactCreateContainer"));
const DealCreateContainer = dynamic(() => import("@/app/components/actions/create/DealCreateContainer"));
const WorkflowManualRunLauncher = dynamic(
    () => import("@/app/components/settings/workflows/manual-runs/WorkflowManualRunLauncher"),
);

const REFERENCE_KINDS: ReadonlySet<OverlayRequest["kind"]> = new Set([
    "create-task",
    "create-note",
    "create-activity",
]);

const INACTIVE_RADAR_TASK_SNAPSHOT: RadarTaskSignalSnapshot = { status: "changed" };

const OVERLAY_LIFECYCLE_CAPABILITIES: Record<
    OverlayRequest["kind"],
    OverlayLifecycleCapabilities
> = {
    "create-task": { reportsMount: true, reportsCloseCompletion: true },
    "create-note": { reportsMount: false, reportsCloseCompletion: false },
    "create-activity": { reportsMount: false, reportsCloseCompletion: false },
    "create-company": { reportsMount: false, reportsCloseCompletion: false },
    "create-person": { reportsMount: false, reportsCloseCompletion: false },
    "create-deal": { reportsMount: false, reportsCloseCompletion: false },
    "import-companies": { reportsMount: false, reportsCloseCompletion: false },
    "import-contacts": { reportsMount: false, reportsCloseCompletion: false },
    "import-deals": { reportsMount: false, reportsCloseCompletion: false },
    "workflow-manual-run": { reportsMount: false, reportsCloseCompletion: false },
};

function subscribeToInactiveRadarTask(): () => void {
    return () => undefined;
}

function getInactiveRadarTaskSnapshot(): RadarTaskSignalSnapshot {
    return INACTIVE_RADAR_TASK_SNAPSHOT;
}

type RestoredDraftMount = {
    accept: (overlayGeneration: number) => void;
    isAccepted: (overlayGeneration: number | undefined) => boolean;
};

type RenderedOverlay = {
    generation: number;
    originWorkspaceId: number | null;
    request: OverlayRequest;
    signal: AbortSignal | null;
};

function createRestoredDraftMount(): RestoredDraftMount {
    let acceptedOverlayGeneration: number | null = null;
    return {
        accept: (overlayGeneration) => {
            acceptedOverlayGeneration = overlayGeneration;
        },
        isAccepted: (overlayGeneration) => acceptedOverlayGeneration === overlayGeneration,
    };
}

function subscribeToDraftGeneration(onStoreChange: () => void): () => void {
    return subscribeDraftChanges(onStoreChange);
}

function getServerDraftGeneration(): number {
    return -1;
}

/** Resolves the person the context refers to from the loaded roster, so the full dialog can preselect it. */
function resolvePerson(persons: Contact[], defaults: CreateDefaults | undefined): Contact | null {
    if (!defaults?.personId) return null;
    return persons.find((person) => person.id === defaults.personId) ?? null;
}

/** Resolves the deal the context refers to from the loaded list, so the full dialog can preselect it. */
function resolveDeal(deals: Deal[], defaults: CreateDefaults | undefined): Deal | null {
    if (!defaults?.dealId) return null;
    return deals.find((deal) => deal.id === defaults.dealId) ?? null;
}

function includeSelected<T extends { id: number }>(items: T[], selected: T | null): T[] {
    if (!selected || items.some((item) => item.id === selected.id)) return items;
    return [selected, ...items];
}

async function loadReferences(
    defaultPersonId: number | undefined,
    defaultDealId: number | undefined,
    init: RequestInit,
    restoredPersonId: number | null | undefined,
    restoredDealId: number | null | undefined,
    rosterOnly: boolean,
) {
    const [fetchedPersons, fetchedDeals] = await Promise.all([
        getContacts({}, init).catch((error: unknown) => {
            if (init.signal?.aborted) throw error;
            if (restoredPersonId != null) throw error;
            return [] as Contact[];
        }),
        getDeals(init).catch((error: unknown) => {
            if (init.signal?.aborted) throw error;
            if (restoredDealId != null) throw error;
            return [] as Deal[];
        }),
    ]);
    const selectedPerson = !rosterOnly && defaultPersonId != null && !fetchedPersons.some((person) => person.id === defaultPersonId)
        ? await getContactById(defaultPersonId, init)
        : null;
    const selectedDeal = !rosterOnly && defaultDealId != null && !fetchedDeals.some((deal) => deal.id === defaultDealId)
        ? await getDealById(defaultDealId, init)
        : null;
    return {
        persons: includeSelected(fetchedPersons, selectedPerson),
        deals: includeSelected(fetchedDeals, selectedDeal),
    };
}

/**
 * Renders the shell-owned create overlays that registry actions open. Each form is code-split and
 * mounted only while its overlay is requested; the reference data a form needs (assignees for tasks,
 * and the person/deal roster so a context prefill can be preselected and stay editable) is fetched on
 * open, so nothing is loaded until the user actually creates something.
 *
 * The requested overlay is kept mounted through its close animation: the `visible` flag drives each
 * dialog's `open` prop and flips to false when the request clears, while `rendered` (and its loaded
 * reference data) persist so the dialog can play its exit transition instead of unmounting instantly.
 * Overlays declare whether they report mount and close completion. Unreported lifecycles release on
 * cancellation, while the task composer remains retained only after it has mounted and until its exit
 * completes.
 */
export default function ActionOverlayHost({
    overlay,
    overlayGeneration,
    originWorkspaceId,
    requestSignal,
    user,
    onClose,
}: {
    overlay: OverlayRequest | null;
    overlayGeneration: number | null;
    originWorkspaceId: number | null;
    requestSignal: AbortSignal | null;
    user: User | null;
    onClose: () => void;
}) {
    const t = useTranslations("Actions");
    const router = useRouter();

    const [retainedOverlay, dispatchRetention] = useReducer(
        reduceOverlayRetention<RenderedOverlay>,
        overlay && overlayGeneration !== null ? {
            generation: overlayGeneration,
            value: {
                generation: overlayGeneration,
                originWorkspaceId,
                request: overlay,
                signal: requestSignal,
            },
            open: true,
            mounted: false,
            capabilities: OVERLAY_LIFECYCLE_CAPABILITIES[overlay.kind],
        } : null,
    );
    if (overlay && overlayGeneration !== null && overlayGeneration !== retainedOverlay?.generation) {
        dispatchRetention({
            type: "opened",
            generation: overlayGeneration,
            value: {
                generation: overlayGeneration,
                originWorkspaceId,
                request: overlay,
                signal: requestSignal,
            },
            capabilities: OVERLAY_LIFECYCLE_CAPABILITIES[overlay.kind],
        });
    } else if (!overlay && retainedOverlay?.open) {
        dispatchRetention({ type: "cancelled", generation: retainedOverlay.generation });
    }
    const rendered = retainedOverlay?.value ?? null;
    const visible = retainedOverlay?.open ?? false;
    const closingGeneration = retainedOverlay !== null
        && !retainedOverlay.open
        && retainedOverlay.mounted
        && retainedOverlay.capabilities.reportsCloseCompletion
        ? retainedOverlay.generation
        : null;
    useEffect(() => {
        if (closingGeneration === null) return;
        let timeout: ReturnType<typeof globalThis.setTimeout> | null = null;
        const frame = globalThis.requestAnimationFrame(() => {
            timeout = globalThis.setTimeout(() => {
                dispatchRetention({
                    type: "retention-expired",
                    generation: closingGeneration,
                });
            }, OVERLAY_MAX_EXIT_DURATION_MS);
        });
        return () => {
            globalThis.cancelAnimationFrame(frame);
            if (timeout !== null) globalThis.clearTimeout(timeout);
        };
    }, [closingGeneration]);
    const requestInit = useMemo<RequestInit>(() => ({
        ...(rendered?.signal ? { signal: rendered.signal } : {}),
        ...(rendered?.originWorkspaceId !== null && rendered?.originWorkspaceId !== undefined
            ? { headers: { "X-Workspace-Id": String(rendered.originWorkspaceId) } }
            : {}),
    }), [rendered]);

    const [loadedUsers, setLoadedUsers] = useState<{ key: string; users: User[] } | null>(null);
    const [loadedReferences, setLoadedReferences] = useState<{
        key: string;
        persons: Contact[];
        deals: Deal[];
    } | null>(null);

    const kind = rendered?.request.kind;
    const radarTask = rendered?.request.kind === "create-task" ? rendered.request.radarTask : undefined;
    const subscribedRadarTask = visible ? radarTask : undefined;
    const needsReference = kind !== undefined && REFERENCE_KINDS.has(kind);
    const needsUsers = kind === "create-task";
    const defaults = rendered && "defaults" in rendered.request ? rendered.request.defaults : undefined;
    const defaultPersonId = defaults?.personId;
    const defaultDealId = defaults?.dealId;
    const rosterOnly = (kind === "create-task" || kind === "create-note" || kind === "create-activity") &&
        rendered !== null &&
        "restoredDraftGeneration" in rendered.request &&
        rendered.request.restoredDraftGeneration !== undefined;
    const restoredPersonId = rosterOnly
        ? rendered.request.kind === "create-task" || rendered.request.kind === "create-note"
            ? rendered.request.draft?.personId
            : defaultPersonId
        : undefined;
    const restoredDealId = rosterOnly
        ? rendered.request.kind === "create-task" || rendered.request.kind === "create-note"
            ? rendered.request.draft?.dealId
            : defaultDealId
        : undefined;
    const restoredAssigneeId = rosterOnly && rendered.request.kind === "create-task"
        ? rendered.request.draft?.assigneeId
        : undefined;
    const restoredDraftGeneration = rosterOnly && "restoredDraftGeneration" in rendered.request
        ? rendered.request.restoredDraftGeneration
        : undefined;
    const restoredDraftFormType = kind === "create-task"
        ? "task"
        : kind === "create-note"
            ? "note"
            : kind === "create-activity"
                ? "activity"
                : null;
    const restoredDraftKey = restoredDraftGeneration !== undefined && restoredDraftFormType !== null
        ? draftKey({
            userId: user?.id ?? null,
            workspaceId: rendered?.originWorkspaceId ?? null,
            formType: restoredDraftFormType,
            scope: "global",
        })
        : null;
    const referenceKey = needsReference
        ? [rendered?.generation, kind, defaultPersonId, defaultDealId, rosterOnly].join(":")
        : null;
    const usersKey = needsUsers ? [rendered?.generation, kind].join(":") : null;
    const [restoredDraftMount] = useState(createRestoredDraftMount);
    const referencesReady = !needsReference || loadedReferences?.key === referenceKey;
    const usersReady = !needsUsers || loadedUsers?.key === usersKey;
    const getRestoredDraftGeneration = useCallback(
        () => restoredDraftMount.isAccepted(rendered?.generation)
            ? restoredDraftGeneration ?? -1
            : restoredDraftKey === null
                ? -1
                : getDraftKeyGeneration(restoredDraftKey),
        [rendered?.generation, restoredDraftGeneration, restoredDraftKey, restoredDraftMount],
    );
    const observedRestoredDraftGeneration = useSyncExternalStore(
        subscribeToDraftGeneration,
        getRestoredDraftGeneration,
        getServerDraftGeneration,
    );
    const radarTaskSnapshot = useSyncExternalStore(
        subscribedRadarTask?.signalState.subscribe ?? subscribeToInactiveRadarTask,
        subscribedRadarTask?.signalState.getSnapshot ?? getInactiveRadarTaskSnapshot,
        getInactiveRadarTaskSnapshot,
    );
    const restoredDraftCanMount = !rosterOnly ||
        observedRestoredDraftGeneration === restoredDraftGeneration;
    const handleRestoredDraftMounted = useCallback(() => {
        if (
            rendered === null ||
            restoredDraftGeneration === undefined ||
            restoredDraftKey === null ||
            getDraftKeyGeneration(restoredDraftKey) !== restoredDraftGeneration
        ) {
            onClose();
            return;
        }
        restoredDraftMount.accept(rendered.generation);
    }, [onClose, rendered, restoredDraftGeneration, restoredDraftKey, restoredDraftMount]);
    const handleTaskDialogMounted = useCallback(() => {
        if (rendered === null) return;
        dispatchRetention({ type: "mounted", generation: rendered.generation });
        if (rosterOnly) handleRestoredDraftMounted();
    }, [handleRestoredDraftMounted, rendered, rosterOnly]);

    useEffect(() => {
        if (!referenceKey) return;
        let cancelled = false;
        loadReferences(
            defaultPersonId,
            defaultDealId,
            requestInit,
            restoredPersonId,
            restoredDealId,
            rosterOnly,
        )
            .then((references) => {
                if (cancelled || requestInit.signal?.aborted) return;
                if (
                    restoredDraftKey !== null &&
                    restoredDraftGeneration !== getDraftKeyGeneration(restoredDraftKey)
                ) {
                    onClose();
                    return;
                }
                if (
                    (restoredPersonId != null && !references.persons.some((person) => person.id === restoredPersonId)) ||
                    (restoredDealId != null && !references.deals.some((deal) => deal.id === restoredDealId))
                ) {
                    toastWarn(t("feedback.restoredLinkUnavailable"));
                }
                setLoadedReferences({ key: referenceKey, ...references });
            })
            .catch(() => {
                if (cancelled || requestInit.signal?.aborted) return;
                toastError(t("feedback.linkedRecordLoadFailed"));
                onClose();
            });
        return () => {
            cancelled = true;
        };
    }, [
        defaultDealId,
        defaultPersonId,
        onClose,
        referenceKey,
        requestInit,
        restoredDealId,
        restoredDraftGeneration,
        restoredDraftKey,
        restoredPersonId,
        rosterOnly,
        t,
    ]);

    useEffect(() => {
        if (!usersKey) return;
        let cancelled = false;
        getUsers(requestInit)
            .then((fetched) => {
                if (cancelled || requestInit.signal?.aborted) return;
                if (
                    restoredDraftKey !== null &&
                    restoredDraftGeneration !== getDraftKeyGeneration(restoredDraftKey)
                ) {
                    onClose();
                    return;
                }
                if (
                    restoredAssigneeId != null &&
                    !fetched.some((candidate) => candidate.id === restoredAssigneeId)
                ) {
                    toastWarn(t("feedback.restoredAssigneeUnavailable"));
                }
                setLoadedUsers({ key: usersKey, users: fetched });
            })
            .catch(() => {
                if (cancelled || requestInit.signal?.aborted) return;
                if (restoredAssigneeId != null) {
                    toastError(t("feedback.linkedRecordLoadFailed"));
                    onClose();
                    return;
                }
                setLoadedUsers({ key: usersKey, users: [] });
            });
        return () => {
            cancelled = true;
        };
    }, [
        onClose,
        requestInit,
        restoredAssigneeId,
        restoredDraftGeneration,
        restoredDraftKey,
        t,
        usersKey,
    ]);

    useLayoutEffect(() => {
        if (
            !rosterOnly ||
            rendered === null ||
            restoredDraftGeneration === undefined ||
            !referencesReady ||
            !usersReady ||
            restoredDraftMount.isAccepted(rendered.generation)
        ) {
            return;
        }
        if (
            restoredDraftKey === null ||
            getDraftKeyGeneration(restoredDraftKey) !== restoredDraftGeneration
        ) {
            onClose();
        }
    }, [
        onClose,
        referencesReady,
        rendered,
        restoredDraftGeneration,
        restoredDraftKey,
        restoredDraftMount,
        rosterOnly,
        usersReady,
    ]);
    const handleOverlayLoadFailure = useCallback(() => {
        if (rendered === null) return;
        dispatchRetention({ type: "load-failed", generation: rendered.generation });
        onClose();
    }, [onClose, rendered]);

    if (!user) return null;

    const handleOpenChange = (open: boolean) => {
        if (!open) onClose();
    };
    const handleRenderedCloseComplete = (generation: number) => {
        dispatchRetention({ type: "close-completed", generation });
    };

    const handleCompaniesImported = () => {
        publishRecordMutation("company");
        router.refresh();
    };
    const handleContactsImported = () => {
        publishRecordMutation("contact");
        router.refresh();
    };

    const references = loadedReferences?.key === referenceKey ? loadedReferences : null;
    const users = loadedUsers?.key === usersKey ? loadedUsers.users : null;
    const persons = references?.persons ?? [];
    const deals = references?.deals ?? [];
    const defaultPerson = resolvePerson(persons, defaults);
    const defaultDeal = resolveDeal(deals, defaults);
    const taskDraft = rendered?.request.kind === "create-task" ? rendered.request.draft : undefined;
    const taskCreateRequest = radarTask
        ? (payload: CreateTaskPayload, init?: RequestInit) => submitRadarTaskWithCurrentSignal(
            radarTask.signalState,
            async (version) => {
                try {
                    const signal = await createRadarTask(
                        radarTask.signalId,
                        version,
                        {
                            ...payload,
                            ...(radarTask.bridgePersonId === undefined
                                ? {}
                                : { bridgePersonId: radarTask.bridgePersonId }),
                        },
                        init,
                    );
                    radarTask.onCreated(signal);
                    return signal;
                } catch (error) {
                    if (error instanceof ApiError && error.status === 409) {
                        radarTask.signalState.refresh(undefined, "checking");
                        radarTask.onRefresh();
                    }
                    throw error;
                }
            },
        )
        : undefined;
    const taskSubmissionBlockedMessage = radarTask === undefined || radarTaskSnapshot.status === "current"
        ? undefined
        : t(radarTaskSnapshot.status === "checking"
            ? "feedback.radarTaskRefreshing"
            : radarTaskSnapshot.status === "unavailable"
                ? "feedback.radarTaskUnavailable"
                : "feedback.radarTaskChanged");
    const taskDefaultAssignee = taskDraft?.assigneeId == null
        ? null
        : users?.find((candidate) => candidate.id === taskDraft.assigneeId) ?? null;
    const taskDefaultPerson = taskDraft && taskDraft.personId !== undefined
        ? persons.find((candidate) => candidate.id === taskDraft.personId) ?? null
        : defaultPerson;
    const taskDefaultDeal = taskDraft && taskDraft.dealId !== undefined
        ? deals.find((candidate) => candidate.id === taskDraft.dealId) ?? null
        : defaultDeal;
    const noteDraft = rendered?.request.kind === "create-note" ? rendered.request.draft : undefined;
    const noteDefaultPerson = noteDraft && noteDraft.personId !== undefined
        ? persons.find((candidate) => candidate.id === noteDraft.personId) ?? null
        : defaultPerson;
    const noteDefaultDeal = noteDraft && noteDraft.dealId !== undefined
        ? deals.find((candidate) => candidate.id === noteDraft.dealId) ?? null
        : defaultDeal;
    const activityDraft = rendered?.request.kind === "create-activity" ? rendered.request.draft : undefined;
    const defaultActivityType = ACTIVITY_TYPES.find((activityType) => activityType === activityDraft?.type);
    const dealDraft = rendered?.request.kind === "create-deal" ? rendered.request.draft : undefined;

    return (
        <OverlayChunkFailureBoundary
            key={rendered?.generation ?? "no-overlay"}
            onFailure={handleOverlayLoadFailure}
        >
            <Fragment key={rendered?.generation}>
                {rendered?.request.kind === "create-task" && users && references && restoredDraftCanMount ? (
                    <TaskDialog
                        open={visible}
                        onOpenChange={handleOpenChange}
                        persons={persons}
                        deals={deals}
                        users={users}
                        currentUserId={user.id}
                        defaultAssignee={taskDefaultAssignee}
                        defaultPerson={taskDefaultPerson}
                        defaultDeal={taskDefaultDeal}
                        defaultDueDate={taskDraft?.dueDate ?? ""}
                        defaultDescription={taskDraft?.description ?? ""}
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
                        onDraftMounted={handleTaskDialogMounted}
                        requestInit={requestInit}
                        createRequest={taskCreateRequest}
                        compact={radarTask?.mode === 'warm_path'}
                        hideLinks={radarTask !== undefined}
                        failureMessage={radarTask ? t('feedback.createFailed') : undefined}
                        draftPersistence={radarTask === undefined}
                        preserveDraftOnClose={radarTask !== undefined}
                        submissionBlockedMessage={taskSubmissionBlockedMessage}
                        onPersistDraft={radarTask?.onDraftChange}
                        onClearDraft={radarTask?.onDraftClear}
                        onCloseComplete={() => handleRenderedCloseComplete(rendered.generation)}
                    />
                ) : null}
                {rendered?.request.kind === "create-note" && references && restoredDraftCanMount ? (
                    <NoteDialog
                        open={visible}
                        onOpenChange={handleOpenChange}
                        note={null}
                        persons={persons}
                        deals={deals}
                        currentUserId={user.id}
                        defaultPerson={noteDefaultPerson}
                        defaultDeal={noteDefaultDeal}
                        defaultContent={noteDraft?.content ?? ""}
                        defaultTitle={noteDraft?.title ?? ""}
                        defaultVisibility={noteDraft?.visibility}
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
                        onDraftMounted={rosterOnly ? handleRestoredDraftMounted : undefined}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "create-activity" && references && restoredDraftCanMount ? (
                    <ActivityDialog
                        open={visible}
                        onOpenChange={handleOpenChange}
                        persons={persons}
                        deals={deals}
                        currentUserId={user.id}
                        defaultPerson={defaultPerson}
                        defaultDeal={defaultDeal}
                        defaultType={defaultActivityType}
                        defaultSubject={activityDraft?.subject ?? ""}
                        defaultNotes={activityDraft?.notes ?? ""}
                        requireRelationshipTarget={rendered.request.requireRelationshipTarget}
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
                        onDraftMounted={rosterOnly ? handleRestoredDraftMounted : undefined}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "create-company" ? (
                    <CompanyCreateContainer open={visible} onOpenChange={handleOpenChange} requestInit={requestInit} />
                ) : null}
                {rendered?.request.kind === "create-person" ? (
                    <ContactCreateContainer open={visible} onOpenChange={handleOpenChange} defaults={rendered.request.defaults} requestInit={requestInit} />
                ) : null}
                {rendered?.request.kind === "create-deal" ? (
                    <DealCreateContainer
                        open={visible}
                        onOpenChange={handleOpenChange}
                        defaults={rendered.request.defaults}
                        currentUserId={user.id}
                        draftPersistence
                        initialDraft={dealDraft}
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "import-companies" ? (
                    <ImportDialog
                        entity="companies"
                        open={visible}
                        onOpenChange={handleOpenChange}
                        onImported={handleCompaniesImported}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "import-contacts" ? (
                    <ImportDialog
                        entity="persons"
                        open={visible}
                        onOpenChange={handleOpenChange}
                        onImported={handleContactsImported}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "import-deals" ? (
                    <ImportDialog
                        entity="deals"
                        open={visible}
                        onOpenChange={handleOpenChange}
                        onImported={() => {}}
                        requestInit={requestInit}
                    />
                ) : null}
                {rendered?.request.kind === "workflow-manual-run" ? (
                    <WorkflowManualRunLauncher
                        key={rendered.generation}
                        open={visible}
                        onOpenChange={handleOpenChange}
                        requestInit={requestInit}
                        recordType={rendered.request.recordType}
                        sourceSurface={rendered.request.sourceSurface}
                        initialScope={rendered.request.scope}
                        initialWorkflowId={rendered.request.workflowId}
                    />
                ) : null}
            </Fragment>
        </OverlayChunkFailureBoundary>
    );
}
