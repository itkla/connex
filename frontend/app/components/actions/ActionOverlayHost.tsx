"use client";

import { useEffect, useState } from "react";
import dynamic from "next/dynamic";
import { useTranslations } from "next-intl";

import { getContactById, getContacts, getDealById, getDeals, getUsers } from "@/app/lib/api";
import type { Contact, Deal, User } from "@/app/lib/types";
import type { CreateDefaults, OverlayRequest } from "@/app/lib/actions/types";
import { ACTIVITY_TYPES } from "@/app/components/activity/activities/activityTypes";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { publishRecordMutation } from "@/app/lib/record-mutation-events";
import { toastError } from "@/app/lib/toast";

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

async function loadReferences(defaultPersonId?: number, defaultDealId?: number) {
    const [fetchedPersons, fetchedDeals] = await Promise.all([
        getContacts({}).catch(() => [] as Contact[]),
        getDeals().catch(() => [] as Deal[]),
    ]);
    const selectedPerson = defaultPersonId != null && !fetchedPersons.some((person) => person.id === defaultPersonId)
        ? await getContactById(defaultPersonId)
        : null;
    const selectedDeal = defaultDealId != null && !fetchedDeals.some((deal) => deal.id === defaultDealId)
        ? await getDealById(defaultDealId)
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
    user,
    onClose,
}: {
    overlay: OverlayRequest | null;
    user: User | null;
    onClose: () => void;
}) {
    const t = useTranslations("Actions");
    const { activeWorkspaceId } = useWorkspace();

    const [rendered, setRendered] = useState<OverlayRequest | null>(overlay);
    if (overlay && overlay !== rendered) setRendered(overlay);
    const visible = overlay != null;

    const [loadedUsers, setLoadedUsers] = useState<{ key: string; users: User[] } | null>(null);
    const [loadedReferences, setLoadedReferences] = useState<{
        key: string;
        persons: Contact[];
        deals: Deal[];
    } | null>(null);

    const kind = rendered?.kind;
    const needsReference = kind !== undefined && REFERENCE_KINDS.has(kind);
    const needsUsers = kind === "create-task";
    const defaults = rendered && "defaults" in rendered ? rendered.defaults : undefined;
    const defaultPersonId = defaults?.personId;
    const defaultDealId = defaults?.dealId;
    const referenceKey = needsReference
        ? [activeWorkspaceId, kind, defaultPersonId, defaultDealId].join(":")
        : null;
    const usersKey = needsUsers ? [activeWorkspaceId, kind].join(":") : null;

    useEffect(() => {
        if (!referenceKey) return;
        let cancelled = false;
        loadReferences(defaultPersonId, defaultDealId)
            .then((references) => {
                if (cancelled) return;
                setLoadedReferences({ key: referenceKey, ...references });
            })
            .catch(() => {
                if (cancelled) return;
                toastError(t("feedback.linkedRecordLoadFailed"));
                onClose();
            });
        return () => {
            cancelled = true;
        };
    }, [defaultDealId, defaultPersonId, onClose, referenceKey, t]);

    useEffect(() => {
        if (!usersKey) return;
        let cancelled = false;
        getUsers()
            .then((fetched) => {
                if (cancelled) return;
                setLoadedUsers({ key: usersKey, users: fetched });
            })
            .catch(() => {
                if (cancelled) return;
                setLoadedUsers({ key: usersKey, users: [] });
            });
        return () => {
            cancelled = true;
        };
    }, [usersKey]);

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

    return (
        <>
            {rendered?.kind === "create-task" && users && references ? (
                <TaskDialog
                    open={visible}
                    onOpenChange={handleOpenChange}
                    persons={persons}
                    deals={deals}
                    users={users}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultDueDate={rendered.draft?.dueDate ?? ""}
                    defaultDescription={rendered.draft?.description ?? ""}
                />
            ) : null}
            {rendered?.kind === "create-note" && references ? (
                <NoteDialog
                    open={visible}
                    onOpenChange={handleOpenChange}
                    note={null}
                    persons={persons}
                    deals={deals}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultContent={rendered.draft?.content ?? ""}
                />
            ) : null}
            {rendered?.kind === "create-activity" && references ? (
                <ActivityDialog
                    open={visible}
                    onOpenChange={handleOpenChange}
                    persons={persons}
                    deals={deals}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultType={ACTIVITY_TYPES.find((activityType) => activityType === rendered.draft?.type)}
                    defaultSubject={rendered.draft?.subject ?? ""}
                    defaultNotes={rendered.draft?.notes ?? ""}
                />
            ) : null}
            {rendered?.kind === "create-company" ? (
                <CompanyCreateContainer open={visible} onOpenChange={handleOpenChange} />
            ) : null}
            {rendered?.kind === "create-person" ? (
                <ContactCreateContainer open={visible} onOpenChange={handleOpenChange} defaults={rendered.defaults} />
            ) : null}
            {rendered?.kind === "create-deal" ? (
                <DealCreateContainer open={visible} onOpenChange={handleOpenChange} defaults={rendered.defaults} />
            ) : null}
            {rendered?.kind === "import-companies" ? (
                <ImportDialog
                    entity="companies"
                    open={visible}
                    onOpenChange={handleOpenChange}
                    onImported={handleCompaniesImported}
                />
            ) : null}
            {rendered?.kind === "import-contacts" ? (
                <ImportDialog
                    entity="persons"
                    open={visible}
                    onOpenChange={handleOpenChange}
                    onImported={handleContactsImported}
                />
            ) : null}
            {rendered?.kind === "import-deals" ? (
                <ImportDialog
                    entity="deals"
                    open={visible}
                    onOpenChange={handleOpenChange}
                    onImported={() => {}}
                />
            ) : null}
        </>
    );
}
