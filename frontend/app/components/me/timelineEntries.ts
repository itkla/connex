import {
    type Activity,
    type ContactLifecycleHistoryEntry,
    type Note,
    type PersonCampaignTouch,
    type RecordComment,
    type RecordCommentThread,
    type Task,
} from "@/app/lib/types";
import { timeOf } from "@/app/lib/utils";

/**
 * One dated thing that happened to a record. D19 phase 1: comments join tasks, activities, notes,
 * and lifecycle changes so a record has one chronology instead of parallel histories.
 * `CommentsSection` remains the composer and thread view — the timeline only carries the event.
 *
 * WS7 adds the campaign touch: a contact's record no longer omits the marketing that reached them.
 * A touch is something the workspace did to the contact rather than something a member wrote, so it
 * is read-only here and the campaign remains the surface that explains it.
 *
 * A touch is dated by when it happened, never by when its delivery row last changed: a bounce
 * receipt arriving months later would otherwise file a January send at the top of today, reading as
 * if the contact had just been emailed. Dating by the delivery's creation also keeps the rendered
 * order a true prefix of the page the server returned, which it orders the same way — a window
 * sorted one way and displayed another is not the newest 50 of anything. The status still reports
 * what became of the touch.
 */
export type TimelineEntry =
    | { kind: 'task'; sortAt: number; task: Task }
    | { kind: 'activity'; sortAt: number; activity: Activity }
    | { kind: 'note'; sortAt: number; note: Note }
    | { kind: 'lifecycle'; sortAt: number; lifecycle: ContactLifecycleHistoryEntry }
    | { kind: 'comment'; sortAt: number; comment: RecordComment }
    | { kind: 'campaign'; sortAt: number; campaign: PersonCampaignTouch };

/**
 * How many comment threads a record page loads for its chronology. The server caps the page at 100;
 * a record's timeline wants recent discussion, not the whole archive, and `CommentsSection` remains
 * the surface that pages through all of it.
 */
export const TIMELINE_COMMENT_LIMIT = 50;

/**
 * How many campaign touches a contact page loads for its chronology. A touch is one delivery, so a
 * heavily campaigned contact could carry thousands; the timeline wants the recent ones, and the
 * campaign itself remains the surface that lists a whole send.
 */
export const TIMELINE_CAMPAIGN_TOUCH_LIMIT = 50;

/**
 * The comments a record's chronology carries, oldest thread structure flattened away.
 *
 * Redacted comments are dropped: their row survives in the thread as a tombstone so the discussion
 * still reads, but a chronology of what happened has nothing to say about a comment whose text is
 * gone.
 */
export function commentsFromThreads(threads: RecordCommentThread[]): RecordComment[] {
    return threads
        .flatMap((thread) => thread.comments)
        .filter((comment) => comment.deletedAt === null && comment.content !== null);
}

/** The id of whoever the entry is attributed to, for the row's avatar. */
export function entryAuthorId(entry: TimelineEntry): number | undefined {
    if (entry.kind === 'task') return entry.task.assignedToId;
    if (entry.kind === 'activity') return entry.activity.createdById;
    if (entry.kind === 'lifecycle') return entry.lifecycle.changedById ?? undefined;
    if (entry.kind === 'comment') return entry.comment.author?.id;
    if (entry.kind === 'campaign') return undefined;
    return entry.note.author;
}

/** The entry's own identity, for the row key. */
export function entryId(entry: TimelineEntry): number {
    if (entry.kind === 'task') return entry.task.id;
    if (entry.kind === 'activity') return entry.activity.id;
    if (entry.kind === 'lifecycle') return entry.lifecycle.id;
    if (entry.kind === 'comment') return entry.comment.id;
    if (entry.kind === 'campaign') return entry.campaign.deliveryId;
    return entry.note.id;
}

/** Merges every kind of record history into one list, newest first. */
export function buildTimeline({
    tasks,
    activities,
    notes,
    lifecycleHistory,
    comments,
    campaignTouches,
}: {
    tasks: Task[];
    activities: Activity[];
    notes: Note[];
    lifecycleHistory: ContactLifecycleHistoryEntry[];
    comments: RecordComment[];
    campaignTouches: PersonCampaignTouch[];
}): TimelineEntry[] {
    const entries: TimelineEntry[] = [
        ...tasks.map<TimelineEntry>((task) => ({
            kind: "task",
            sortAt: timeOf(task.updatedAt) || timeOf(task.createdAt),
            task,
        })),
        ...activities.map<TimelineEntry>((activity) => ({
            kind: "activity",
            sortAt: timeOf(activity.timestamp),
            activity,
        })),
        ...notes.map<TimelineEntry>((note) => ({
            kind: "note",
            sortAt: timeOf(note.updatedAt) || timeOf(note.createdAt),
            note,
        })),
        ...lifecycleHistory.map<TimelineEntry>((lifecycle) => ({
            kind: "lifecycle",
            sortAt: timeOf(lifecycle.changedAt),
            lifecycle,
        })),
        ...comments.map<TimelineEntry>((comment) => ({
            kind: "comment",
            sortAt: timeOf(comment.editedAt ?? undefined) || timeOf(comment.createdAt),
            comment,
        })),
        ...campaignTouches.map<TimelineEntry>((campaign) => ({
            kind: "campaign",
            sortAt: timeOf(campaign.createdAt),
            campaign,
        })),
    ];
    return entries.sort((a, b) => b.sortAt - a.sortAt);
}
