import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getNotes,
    getUsers,
} from "@/app/lib/api";
import NotesBrowser from "@/app/components/activity/notes/NotesBrowser";

export default async function NotesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' as const };

    const [allNotes, persons, deals, users] = await Promise.all([
        getNotes(init),
        getContacts({}, init),
        getDeals(init),
        getUsers(init),
    ]);

    return (
        <NotesBrowser
            notes={allNotes}
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
        />
    );
}
