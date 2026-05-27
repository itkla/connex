import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
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
    const t = await getTranslations("ActivityNotes");
    const allNotes = await getAllNotes();
    // console.log(allNotes);
    const userNotes = allNotes.filter((note) => note.author === user?.id);
    return (
        <div>
            <h1>{t("title")}</h1>
            <h2>{t("myNotes")}</h2>
            <ul>
                {userNotes.map((note) => (
                    <li key={note.id}>{note.content}</li>
                ))}
            </ul>
            <h2>{t("allNotes")}</h2>
            <ul>
                {allNotes.map((note) => (
                    <li key={note.id}>{note.content}</li>
                ))}
            </ul>
        </div>
    )
}
