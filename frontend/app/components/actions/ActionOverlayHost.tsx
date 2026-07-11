"use client";

import { useEffect, useState } from "react";
import dynamic from "next/dynamic";

import { getUsers } from "@/app/lib/api";
import type { User } from "@/app/lib/types";
import type { OverlayRequest } from "@/app/lib/actions/types";

const TaskDialog = dynamic(() => import("@/app/components/activity/tasks/TaskDialog"));
const NoteDialog = dynamic(() => import("@/app/components/activity/notes/NoteDialog"));
const ActivityDialog = dynamic(() => import("@/app/components/activity/activities/ActivityDialog"));
const CompanyCreateContainer = dynamic(() => import("@/app/components/actions/create/CompanyCreateContainer"));
const ContactCreateContainer = dynamic(() => import("@/app/components/actions/create/ContactCreateContainer"));
const DealCreateContainer = dynamic(() => import("@/app/components/actions/create/DealCreateContainer"));

/**
 * Renders the shell-owned create overlays that registry actions open. Each form is code-split and
 * mounted only while its overlay is requested, and the reference data a form needs (task assignees) is
 * fetched on open — so nothing is loaded until the user actually creates something. Quick Create (#403)
 * extends this with context-aware prefills and the company/person/deal forms.
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
    const [usersLoaded, setUsersLoaded] = useState(false);

    useEffect(() => {
        if (overlay?.kind !== "create-task") return;
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
    }, [overlay?.kind]);

    if (!user) return null;

    const handleOpenChange = (open: boolean) => {
        if (!open) onClose();
    };

    return (
        <>
            {overlay?.kind === "create-task" && usersLoaded ? (
                <TaskDialog
                    open
                    onOpenChange={handleOpenChange}
                    persons={[]}
                    deals={[]}
                    users={users}
                    currentUserId={user.id}
                />
            ) : null}
            {overlay?.kind === "create-note" ? (
                <NoteDialog
                    open
                    onOpenChange={handleOpenChange}
                    note={null}
                    persons={[]}
                    deals={[]}
                    currentUserId={user.id}
                />
            ) : null}
            {overlay?.kind === "create-activity" ? (
                <ActivityDialog open onOpenChange={handleOpenChange} persons={[]} deals={[]} currentUserId={user.id} />
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
