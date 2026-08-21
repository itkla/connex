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

export default async function NotesPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
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
