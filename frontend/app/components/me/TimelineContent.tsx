"use client";

import { useCallback } from "react";
import { useTranslations } from "next-intl";
import { getNotesForDeal, getNotesForPerson, getUserNotes } from "@/app/lib/api";
import { isProviderOwnedActivity } from "@/app/lib/connectedCapture";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { NOTE_PAGE_SIZE, useNotePages } from "@/app/hooks/useNotePages";
import NotePageControl from "@/app/components/activity/notes/NotePageControl";
import { buildTimeline, entryAuthorId, entryId } from "./timelineEntries";
import TimelineDeepLinkFallback from "./TimelineDeepLinkFallback";
import TimelineRow from "./TimelineRow";
import type { NotePageCursor } from "@/app/lib/types";
import type { TimelineProps } from "./Timeline";

/** Renders record history with explicit continuation for SQL-bounded note previews. */
export default function TimelineContent(props: TimelineProps & { originWorkspaceId: number | null }) {
    const { activeWorkspaceId, switching } = useWorkspace();
    if (switching || activeWorkspaceId !== props.originWorkspaceId) return null;
    return <ScopedTimelineContent {...props} />;
}

function ScopedTimelineContent({
    tasks, activities, notes, users = [], persons = [], deals = [], lifecycleHistory = [],
    comments = [], campaignTouches = [], currentUserId, companyId, limit, noteTarget, originWorkspaceId,
}: TimelineProps & { originWorkspaceId: number | null }) {
    const t = useTranslations("MeTimeline");
    const loadPage = useCallback((page: number, init: RequestInit, cursor?: NotePageCursor) => {
        if (!noteTarget) return Promise.resolve([]);
        const fetchNotes = noteTarget.type === "person" ? getNotesForPerson
            : noteTarget.type === "deal" ? getNotesForDeal : getUserNotes;
        return fetchNotes(noteTarget.id, { page, size: NOTE_PAGE_SIZE, ...cursor }, init);
    }, [noteTarget]);
    const { notes: loadedNotes, loading, hasMore, failed, loadMore } = useNotePages(
        noteTarget ? loadPage : undefined, notes);
    const entries = buildTimeline({ tasks, activities, notes: loadedNotes, lifecycleHistory, comments, campaignTouches });
    const visible = limit ? entries.slice(0, limit) : entries;
    const visibleIds = {
        task: visible.flatMap((entry) => entry.kind === "task" ? [entry.task.id] : []),
        activity: visible.flatMap((entry) => entry.kind === "activity" ? [entry.activity.id] : []),
        note: visible.flatMap((entry) => entry.kind === "note" ? [entry.note.id] : []),
    };
    const knownIds = {
        task: tasks.map((task) => task.id),
        activity: activities.flatMap((activity) => isProviderOwnedActivity(activity) ? [] : [activity.id]),
        note: loadedNotes.map((note) => note.id),
    };

    const userById = new Map(users.map((u) => [u.id, u]));

    return (
        <>
            <TimelineDeepLinkFallback visible={visibleIds} known={knownIds} />
            {visible.length === 0 ? (
                <p className="px-6 py-12 text-center text-sm text-muted-foreground">
                    {t("emptyState")}
                </p>
            ) : (
                <ul className="divide-y divide-border">
                    {visible.map((entry) => {
                        const authorId = entryAuthorId(entry);
                        const author = authorId != null ? userById.get(authorId) : undefined;
                        return (
                            <TimelineRow
                                key={`${entry.kind}-${entryId(entry)}`}
                                entry={entry}
                                author={author}
                                persons={persons}
                                deals={deals}
                                currentUserId={currentUserId}
                                companyId={companyId ?? null}
                                originWorkspaceId={originWorkspaceId}
                            />
                        );
                    })}
                </ul>
            )}
            {hasMore && <NotePageControl loading={loading} failed={failed} onLoadMore={loadMore} />}
        </>
    );
}
