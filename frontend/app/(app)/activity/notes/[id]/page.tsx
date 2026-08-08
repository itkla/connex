import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { loadRecord } from "@/app/lib/recordAccess";
import {
    getContactsFromCookie,
    getCurrentUserResultFromCookie,
    getDealsFromCookie,
    getNoteById,
    getUsers,
} from "@/app/lib/api";
import type { Note, User } from "@/app/lib/types";
import NoteEditorView from "@/app/components/activity/notes/NoteEditorView";
import { resolveRecordReturnPath } from "@/app/lib/recordReturnPath";

export default async function NoteEditorPage({
    params,
    searchParams,
}: {
    params: Promise<{ id: string }>;
    searchParams: Promise<{ returnTo?: string | string[] }>;
}) {
    const [{ id }, query] = await Promise.all([params, searchParams]);
    const returnPath = resolveRecordReturnPath("notes", query.returnTo);
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect("/auth/login");
    }

    const init = cookie ? { headers: { cookie }, cache: "no-store" as const } : undefined;
    const isNew = id === "new";

    let note: Note | null = null;
    if (!isNew) {
        const numericId = Number(id);
        if (!Number.isInteger(numericId)) {
            notFound();
        }
        const noteAccess = await loadRecord(() => getNoteById(numericId, init));
        if (noteAccess.kind === "forbidden") {
            return <AccessDeniedPage />;
        }
        if (noteAccess.kind === "missing") {
            notFound();
        }
        note = noteAccess.record;
    }

    const [persons, deals, users] = await Promise.all([
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
    ]);

    return (
        <NoteEditorView
            note={note}
            currentUserId={user.id}
            persons={persons}
            deals={deals}
            users={users}
            returnPath={returnPath}
        />
    );
}
