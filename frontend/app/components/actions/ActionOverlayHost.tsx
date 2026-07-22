"use client";

import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useState, useSyncExternalStore } from "react";
import dynamic from "next/dynamic";
import { useTranslations } from "next-intl";

import { getContactById, getContacts, getDealById, getDeals, getUsers } from "@/app/lib/api";
import type { Contact, Deal, User } from "@/app/lib/types";
import type { CreateDefaults, OverlayRequest } from "@/app/lib/actions/types";
import { ACTIVITY_TYPES } from "@/app/components/activity/activities/activityTypes";
import { publishRecordMutation } from "@/app/lib/record-mutation-events";
import { toastError, toastWarn } from "@/app/lib/toast";
import { draftKey, getDraftKeyGeneration, subscribeDraftChanges } from "@/app/lib/formDrafts";

const TaskDialog = dynamic(() => import("@/app/components/activity/tasks/TaskDialog"));
const NoteDialog = dynamic(() => import("@/app/components/activity/notes/NoteDialog"));
const ActivityDialog = dynamic(() => import("@/app/components/activity/activities/ActivityDialog"));
const CompanyCreateContainer = dynamic(() => import("@/app/components/actions/create/CompanyCreateContainer"));
const ContactCreateContainer = dynamic(() => import("@/app/components/actions/create/ContactCreateContainer"));
const DealCreateContainer = dynamic(() => import("@/app/components/actions/create/DealCreateContainer"));
const ImportDialog = dynamic(() => import("@/app/components/import/ImportDialog"));

const REFERENCE_KINDS: ReadonlySet<OverlayRequest["kind"]> = new Set([
    "create-task",
    "create-note",
    "create-activity",
]);

type RestoredDraftMount = {
    accept: (overlayGeneration: number) => void;
    isAccepted: (overlayGeneration: number | undefined) => boolean;
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

    const [rendered, setRendered] = useState<{
        generation: number;
        originWorkspaceId: number | null;
        request: OverlayRequest;
        signal: AbortSignal | null;
    } | null>(() => overlay && overlayGeneration !== null ? {
        generation: overlayGeneration,
        originWorkspaceId,
        request: overlay,
        signal: requestSignal,
    } : null);
    if (overlay && overlayGeneration !== null && overlayGeneration !== rendered?.generation) {
        setRendered({
            generation: overlayGeneration,
            originWorkspaceId,
            request: overlay,
            signal: requestSignal,
        });
    }
    const visible = overlay != null;
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
    const needsReference = kind !== undefined && REFERENCE_KINDS.has(kind);
    const needsUsers = kind === "create-task";
    const defaults = rendered && "defaults" in rendered.request ? rendered.request.defaults : undefined;
    const defaultPersonId = defaults?.personId;
    const defaultDealId = defaults?.dealId;
    const rosterOnly = rendered !== null &&
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
    const subscribeToRestoredDraftGeneration = useCallback(
        (onStoreChange: () => void) => {
            const unsubscribe = subscribeDraftChanges(onStoreChange);
            if (
                rosterOnly &&
                referencesReady &&
                usersReady &&
                rendered !== null &&
                restoredDraftGeneration !== undefined &&
                restoredDraftKey !== null &&
                getDraftKeyGeneration(restoredDraftKey) === restoredDraftGeneration
            ) {
                restoredDraftMount.accept(rendered.generation);
            }
            return unsubscribe;
        },
        [
            referencesReady,
            rendered,
            restoredDraftGeneration,
            restoredDraftKey,
            restoredDraftMount,
            rosterOnly,
            usersReady,
        ],
    );
    const observedRestoredDraftGeneration = useSyncExternalStore(
        subscribeToRestoredDraftGeneration,
        getRestoredDraftGeneration,
        getServerDraftGeneration,
    );
    const restoredDraftCanMount = !rosterOnly ||
        observedRestoredDraftGeneration === restoredDraftGeneration;

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

    if (!user) return null;

    const handleOpenChange = (open: boolean) => {
        if (!open) onClose();
    };

    const handleCompaniesImported = () => publishRecordMutation("company");
    const handleContactsImported = () => publishRecordMutation("contact");

    const references = loadedReferences?.key === referenceKey ? loadedReferences : null;
    const users = loadedUsers?.key === usersKey ? loadedUsers.users : null;
    const persons = references?.persons ?? [];
    const deals = references?.deals ?? [];
    const defaultPerson = resolvePerson(persons, defaults);
    const defaultDeal = resolveDeal(deals, defaults);
    const taskDraft = rendered?.request.kind === "create-task" ? rendered.request.draft : undefined;
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

    return (
        <>
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
                        requestInit={requestInit}
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
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
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
                        initialDraftGeneration={rendered.request.restoredDraftGeneration}
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
                    <DealCreateContainer open={visible} onOpenChange={handleOpenChange} defaults={rendered.request.defaults} requestInit={requestInit} />
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
            </Fragment>
        </>
    );
}
