"use client";

import { useCallback } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { DocumentTextIcon } from "@heroicons/react/24/outline";
import type { NoteReferenceType } from "@/app/lib/types";
import { getNotesReferencing } from "@/app/lib/api";
import { deriveNoteTitle, noteSnippet } from "@/app/lib/noteText";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { NOTE_PAGE_SIZE, useNotePages } from "@/app/hooks/useNotePages";
import NotePageControl from "./NotePageControl";
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
export default function BacklinksPanel(props: Props) {
    const { activeWorkspaceId, switching } = useWorkspace();
    if (switching) return null;
    return <ScopedBacklinksPanel key={`${activeWorkspaceId}:${props.refType}:${props.refId}:${props.excludeNoteId}`} {...props} />;
}

function ScopedBacklinksPanel({ refType, refId, excludeNoteId }: Props) {
    const t = useTranslations("ActivityNotesEditor");
    const loadPage = useCallback((page: number, init: RequestInit) =>
        getNotesReferencing(refType, refId, { page, size: NOTE_PAGE_SIZE }, init), [refType, refId]);
    const { notes: loaded, loading, hasMore, failed, loadMore } = useNotePages(loadPage);
    const notes = loaded.filter((note) => note.id !== excludeNoteId);

    if (notes.length === 0 && !hasMore) return null;

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
            {hasMore && <NotePageControl loading={loading} failed={failed} onLoadMore={loadMore} />}
        </section>
    );
}
