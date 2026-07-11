"use client";

import { useEffect, useState } from "react";
import dynamic from "next/dynamic";

import { getContacts, getDeals, getUsers } from "@/app/lib/api";
import type { Contact, Deal, User } from "@/app/lib/types";
import type { CreateDefaults, OverlayRequest } from "@/app/lib/actions/types";
import { ACTIVITY_TYPES } from "@/app/components/activity/activities/activityTypes";

const TaskDialog = dynamic(() => import("@/app/components/activity/tasks/TaskDialog"));
const NoteDialog = dynamic(() => import("@/app/components/activity/notes/NoteDialog"));
const ActivityDialog = dynamic(() => import("@/app/components/activity/activities/ActivityDialog"));
const CompanyCreateContainer = dynamic(() => import("@/app/components/actions/create/CompanyCreateContainer"));
const ContactCreateContainer = dynamic(() => import("@/app/components/actions/create/ContactCreateContainer"));
const DealCreateContainer = dynamic(() => import("@/app/components/actions/create/DealCreateContainer"));

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

/**
 * Renders the shell-owned create overlays that registry actions open. Each form is code-split and
 * mounted only while its overlay is requested; the reference data a form needs (assignees for tasks,
 * and the person/deal roster so a context prefill can be preselected and stay editable) is fetched on
 * open, so nothing is loaded until the user actually creates something. Company, person and deal use
 * self-contained containers that load their own reference data.
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
    const [users, setUsers] = useState<User[]>([]);
    const [persons, setPersons] = useState<Contact[]>([]);
    const [deals, setDeals] = useState<Deal[]>([]);
    const [referenceLoaded, setReferenceLoaded] = useState(false);
    const [usersLoaded, setUsersLoaded] = useState(false);

    const kind = overlay?.kind;
    const needsReference = kind !== undefined && REFERENCE_KINDS.has(kind);
    const needsUsers = kind === "create-task";

    useEffect(() => {
        if (!needsReference) return;
        let cancelled = false;
        Promise.all([getContacts({}).catch(() => [] as Contact[]), getDeals().catch(() => [] as Deal[])])
            .then(([fetchedPersons, fetchedDeals]) => {
                if (cancelled) return;
                setPersons(fetchedPersons);
                setDeals(fetchedDeals);
                setReferenceLoaded(true);
            });
        return () => {
            cancelled = true;
        };
    }, [needsReference, kind]);

    useEffect(() => {
        if (!needsUsers) return;
        let cancelled = false;
        getUsers()
            .then((fetched) => {
                if (cancelled) return;
                setUsers(fetched);
                setUsersLoaded(true);
            })
            .catch(() => {
                if (cancelled) return;
                setUsers([]);
                setUsersLoaded(true);
            });
        return () => {
            cancelled = true;
        };
    }, [needsUsers, kind]);

    if (!user) return null;

    const handleOpenChange = (open: boolean) => {
        if (!open) onClose();
    };

    const defaults = overlay && "defaults" in overlay ? overlay.defaults : undefined;
    const defaultPerson = resolvePerson(persons, defaults);
    const defaultDeal = resolveDeal(deals, defaults);

    return (
        <>
            {overlay?.kind === "create-task" && usersLoaded && referenceLoaded ? (
                <TaskDialog
                    open
                    onOpenChange={handleOpenChange}
                    persons={persons}
                    deals={deals}
                    users={users}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultDueDate={overlay.draft?.dueDate ?? ""}
                    defaultDescription={overlay.draft?.description ?? ""}
                />
            ) : null}
            {overlay?.kind === "create-note" && referenceLoaded ? (
                <NoteDialog
                    open
                    onOpenChange={handleOpenChange}
                    note={null}
                    persons={persons}
                    deals={deals}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultContent={overlay.draft?.content ?? ""}
                />
            ) : null}
            {overlay?.kind === "create-activity" && referenceLoaded ? (
                <ActivityDialog
                    open
                    onOpenChange={handleOpenChange}
                    persons={persons}
                    deals={deals}
                    currentUserId={user.id}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultType={ACTIVITY_TYPES.find((activityType) => activityType === overlay.draft?.type)}
                    defaultSubject={overlay.draft?.subject ?? ""}
                    defaultNotes={overlay.draft?.notes ?? ""}
                />
            ) : null}
            {overlay?.kind === "create-company" ? (
                <CompanyCreateContainer open onOpenChange={handleOpenChange} />
            ) : null}
            {overlay?.kind === "create-person" ? (
                <ContactCreateContainer open onOpenChange={handleOpenChange} defaults={overlay.defaults} />
            ) : null}
            {overlay?.kind === "create-deal" ? (
                <DealCreateContainer open onOpenChange={handleOpenChange} defaults={overlay.defaults} />
            ) : null}
        </>
    );
}
