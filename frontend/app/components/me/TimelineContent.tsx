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
import type { Contact, ContactLifecycleHistoryEntry, Deal, NotePageCursor, PersonCampaignTouch, RecordComment, UserReference } from "@/app/lib/types";
import { useDetailSections } from "@/app/hooks/useDetailSections";
import { useDetailTimelineUsers } from "@/app/hooks/useDetailTimelineUsers";
import DetailSectionState from "@/app/components/records/DetailSectionState";
import type { TimelineProps } from "./Timeline";

const EMPTY_USERS: UserReference[] = [];
const EMPTY_PERSONS: Contact[] = [];
const EMPTY_DEALS: Deal[] = [];
const EMPTY_LIFECYCLE_HISTORY: ContactLifecycleHistoryEntry[] = [];
const EMPTY_COMMENTS: RecordComment[] = [];
const EMPTY_CAMPAIGN_TOUCHES: PersonCampaignTouch[] = [];

/** Renders record history with explicit continuation for SQL-bounded note previews. */
export default function TimelineContent(props: TimelineProps & { originWorkspaceId: number | null }) {
    const { activeWorkspaceId, switching } = useWorkspace();
    if (switching || activeWorkspaceId !== props.originWorkspaceId) return null;
    return props.detailKind ? <DetailTimelineContent {...props} /> : <ScopedTimelineContent {...props} />;
}

function DetailTimelineContent(props: TimelineProps & { originWorkspaceId: number | null }) {
    const sections = useDetailSections();
    const t = useTranslations(props.detailKind === "deal" ? "DealsPage" : props.detailKind === "company" ? "CompaniesDetail" : "ContactsPage");
    const result = sections?.results;
    const users = useDetailTimelineUsers(props.detailKind, props.users ?? EMPTY_USERS, props.originWorkspaceId);
    const activities = props.detailKind === "deal" && result?.dealActivities?.kind === "loaded" ? result.dealActivities.data : props.activities;
    const tasks = props.detailKind === "deal" && result?.dealTasks?.kind === "loaded" ? result.dealTasks.data : props.tasks;
    const notes = props.detailKind === "deal" && result?.dealNotes?.kind === "loaded" ? result.dealNotes.data : props.notes;
    const lifecycleHistory = props.detailKind === "contact" && result?.contactHistory?.kind === "loaded" ? result.contactHistory.data : props.lifecycleHistory;
    const persons = props.detailKind === "company" ? (result?.companyPeople?.kind === "loaded" ? result.companyPeople.data : []) : props.persons;
    const deals = props.detailKind === "company" ? (result?.companyDeals?.kind === "loaded" ? result.companyDeals.data : []) : props.deals;
    const content = <ScopedTimelineContent {...props} users={users} activities={activities} tasks={tasks} notes={notes} lifecycleHistory={lifecycleHistory} persons={persons} deals={deals}
        personsUnavailable={props.detailKind === "company" && result?.companyPeople?.kind === "failed"}
        dealsUnavailable={props.detailKind === "company" && result?.companyDeals?.kind === "failed"}
    />;
    if (props.detailKind === "company") return content;
    return <DetailSectionState title={t("timeline")} sectionKeys={props.detailKind === "deal" ? ["dealActivities", "dealNotes", "dealTasks"] : ["contactHistory"]}>
        {content}
    </DetailSectionState>;
}

function ScopedTimelineContent({
    tasks, activities, notes, users = EMPTY_USERS, persons = EMPTY_PERSONS, deals = EMPTY_DEALS, lifecycleHistory = EMPTY_LIFECYCLE_HISTORY,
    personsUnavailable, dealsUnavailable,
    comments = EMPTY_COMMENTS, campaignTouches = EMPTY_CAMPAIGN_TOUCHES, currentUserId, companyId, limit, noteTarget, originWorkspaceId,
}: TimelineProps & { originWorkspaceId: number | null; personsUnavailable?: boolean; dealsUnavailable?: boolean }) {
    const t = useTranslations("MeTimeline");
    const loadPage = useCallback((page: number, init: RequestInit, cursor?: NotePageCursor) => {
        if (!noteTarget) return Promise.resolve([]);
        const fetchNotes = noteTarget.type === "person" ? getNotesForPerson
            : noteTarget.type === "deal" ? getNotesForDeal : getUserNotes;
        return fetchNotes(noteTarget.id, { page: cursor ? 1 : page, size: NOTE_PAGE_SIZE, ...cursor }, init);
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
                                personsUnavailable={personsUnavailable}
                                dealsUnavailable={dealsUnavailable}
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
