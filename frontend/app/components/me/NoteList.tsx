// NOTE: again, not used anymore but keeping it just in case

import { getLocale, getTranslations } from "next-intl/server";

import { type Note } from "@/app/lib/types";
import EmptyState from "./EmptyState";
import { timeOf, formatShortDate } from "@/app/lib/utils";

export default async function NoteList({ notes }: { notes: Note[] }) {
    const t = await getTranslations("MeNoteList");
    const locale = await getLocale();

    if (notes.length === 0) {
        return <EmptyState message={t("empty")} />;
    }

    const sorted = [...notes].sort((a, b) => timeOf(b.createdAt) - timeOf(a.createdAt));
    const recent = sorted.slice(0, 5);

    return (
        <ul className="divide-y divide-border">
            {recent.map((note) => (
                <li key={note.id} className="flex flex-col gap-1 px-6 py-3">
                    <div className="flex items-start justify-between gap-4">
                        <span className="line-clamp-2 text-sm text-foreground">
                            {note.content}
                        </span>
                        {note.createdAt ? (
                            <span className="shrink-0 text-xs text-muted-foreground">
                                {formatShortDate(note.createdAt, locale)}
                            </span>
                        ) : null}
                    </div>
                </li>
            ))}
        </ul>
    );
}