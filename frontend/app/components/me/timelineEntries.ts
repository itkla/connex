import {
    type Activity,
    type ContactLifecycleHistoryEntry,
    type Note,
    type RecordComment,
    type RecordCommentThread,
    type Task,
} from "@/app/lib/types";
import { timeOf } from "@/app/lib/utils";

/**
 * One dated thing that happened to a record. D19 phase 1: comments join tasks, activities, notes,
 * and lifecycle changes so a record has one chronology instead of parallel histories.
 * `CommentsSection` remains the composer and thread view — the timeline only carries the event.
 */
export type TimelineEntry =
    | { kind: 'task'; sortAt: number; task: Task }
    | { kind: 'activity'; sortAt: number; activity: Activity }
    | { kind: 'note'; sortAt: number; note: Note }
    | { kind: 'lifecycle'; sortAt: number; lifecycle: ContactLifecycleHistoryEntry }
    | { kind: 'comment'; sortAt: number; comment: RecordComment };

/**
 * How many comment threads a record page loads for its chronology. The server caps the page at 100;
 * a record's timeline wants recent discussion, not the whole archive, and `CommentsSection` remains
 * the surface that pages through all of it.
 */
export const TIMELINE_COMMENT_LIMIT = 50;

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
    return entry.note.author;
}

/** The entry's own identity, for the row key. */
export function entryId(entry: TimelineEntry): number {
    if (entry.kind === 'task') return entry.task.id;
    if (entry.kind === 'activity') return entry.activity.id;
    if (entry.kind === 'lifecycle') return entry.lifecycle.id;
    if (entry.kind === 'comment') return entry.comment.id;
    return entry.note.id;
}

/** Merges every kind of record history into one list, newest first. */
export function buildTimeline({
    tasks,
    activities,
    notes,
    lifecycleHistory,
    comments,
}: {
    tasks: Task[];
    activities: Activity[];
    notes: Note[];
    lifecycleHistory: ContactLifecycleHistoryEntry[];
    comments: RecordComment[];
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
    ];
    return entries.sort((a, b) => b.sortAt - a.sortAt);
}
