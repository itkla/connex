import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie, getNotesFromCookie } from "@/app/lib/api";

const cookie = (await headers()).get('cookie');
const user = await getCurrentUserFromCookie(cookie);
if (!user) {
    redirect('/auth/login');
}

async function getAllNotes() {
    return await getNotesFromCookie(cookie);
}

export default async function NotesPage() {
    const allNotes = await getAllNotes();
    // console.log(allNotes);
    const userNotes = allNotes.filter((note) => note.author === user?.id);
    return (
        <div>
            <h1>Notes</h1>
            <h2>My Notes</h2>
            <ul>
                {userNotes.map((note) => (
                    <li key={note.id}>{note.content}</li>
                ))}
            </ul>
            <h2>All Notes</h2>
            <ul>
                {allNotes.map((note) => (
                    <li key={note.id}>{note.content}</li>
                ))}
            </ul>
        </div>
    )
}