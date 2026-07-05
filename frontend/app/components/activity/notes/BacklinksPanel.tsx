"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { DocumentTextIcon } from "@heroicons/react/24/outline";
import type { Note, NoteReferenceType } from "@/app/lib/types";
import { getNotesReferencing } from "@/app/lib/api";
import { deriveNoteTitle, noteSnippet } from "@/app/lib/noteText";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

type Props = {
    refType: NoteReferenceType;
    refId: number;
    excludeNoteId?: number;
};

/**
 * "Referenced by" panel: the notes visible to the caller that reference the
 * given entity. Private source notes are already filtered server-side, so this
 * never surfaces a private note to a non-author. Renders nothing when empty.
 */
export default function BacklinksPanel({ refType, refId, excludeNoteId }: Props) {
    const t = useTranslations("ActivityNotesEditor");
    const [notes, setNotes] = useState<Note[] | null>(null);

    useEffect(() => {
        let active = true;
        getNotesReferencing(refType, refId)
            .then((result) => {
                if (active) setNotes(result.filter((note) => note.id !== excludeNoteId));
            })
            .catch(() => {
                if (active) setNotes([]);
            });
        return () => {
            active = false;
        };
    }, [refType, refId, excludeNoteId]);

    if (notes === null || notes.length === 0) {
        return null;
    }

    return (
        <section className="mt-8">
            <SectionHeader title={t("backlinksTitle")} />
            <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {notes.map((note) => (
                    <li key={note.id}>
                        <Link
                            href={`/activity/notes/${note.id}`}
                            className="flex items-start gap-3 px-6 py-3.5 transition-colors hover:bg-muted/40"
                        >
                            <DocumentTextIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                            <span className="min-w-0 flex-1">
                                <span className="block truncate font-medium text-foreground">
                                    {deriveNoteTitle(note, t("untitled"))}
                                </span>
                                {noteSnippet(note.content) ? (
                                    <span className="mt-0.5 block truncate text-sm text-muted-foreground">
                                        {noteSnippet(note.content)}
                                    </span>
                                ) : null}
                            </span>
                        </Link>
                    </li>
                ))}
            </ul>
        </section>
    );
}
