import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getCurrentUserFromCookie,
    getNotesFromCookie,
    getContactsFromCookie,
    getDealsFromCookie,
    getUsers,
} from "@/app/lib/api";
import type { User } from "@/app/lib/types";
import NotesBrowser from "@/app/components/activity/notes/NotesBrowser";

export default async function NotesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = cookie ? { headers: { cookie }, cache: 'no-store' as const } : undefined;

    const [allNotes, persons, deals, users] = await Promise.all([
        getNotesFromCookie(cookie),
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
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
