import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getContacts,
    getCurrentUserResultFromCookie,
    getDeals,
    getUsers,
} from "@/app/lib/api";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import NotesBrowser from "@/app/components/activity/notes/NotesBrowser";
import { NOTE_URL_KEY, parseDeepLinkId } from "@/app/hooks/listStateUrl";

type NotesPageProps = {
    searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function NotesPage({ searchParams }: NotesPageProps) {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }

    const noteParam = (await searchParams)[NOTE_URL_KEY];
    const rawNoteId = Array.isArray(noteParam) ? noteParam[0] : noteParam;
    const noteId = parseDeepLinkId(rawNoteId ?? null);
    if (noteId !== null) {
        redirect(`/activity/notes/${noteId}`);
    }

    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' as const };

    const [persons, deals, users] = await Promise.all([
        getContacts({}, init),
        getDeals(init),
        getUsers(init),
    ]);

    return (
        <NotesBrowser
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
        />
    );
}
