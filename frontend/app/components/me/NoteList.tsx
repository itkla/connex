import { getLocale, getTranslations } from "next-intl/server";
import { DocumentTextIcon } from "@heroicons/react/24/outline";

import { type Note } from "@/app/lib/types";
import { EmptyState } from "@/app/components/EmptyState";
import NoteContent from "@/app/components/activity/notes/NoteContent";
import { timeOf, formatShortDate } from "@/app/lib/utils";

export default async function NoteList({ notes }: { notes: Note[] }) {
    const t = await getTranslations("MeNoteList");
    const locale = await getLocale();

    if (notes.length === 0) {
        return (
            <EmptyState
                variant="inline"
                tone="muted"
                icon={DocumentTextIcon}
                title={t("emptyTitle")}
                body={t("empty")}
            />
        );
    }

    const sorted = [...notes].sort((a, b) => timeOf(b.createdAt) - timeOf(a.createdAt));
    const recent = sorted.slice(0, 5);

    return (
        <ul className="divide-y divide-border">
            {recent.map((note) => (
                <li key={note.id} className="flex flex-col gap-1 px-6 py-3">
                    <div className="flex items-start justify-between gap-4">
                        <NoteContent
                            content={note.content}
                            references={note.references}
                            className="line-clamp-2 text-sm text-foreground"
                        />
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
