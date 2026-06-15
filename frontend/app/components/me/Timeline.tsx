import { getTranslations } from "next-intl/server";

import { type Activity, type Contact, type Deal, type Note, type Task, type User } from "@/app/lib/types";
import { timeOf } from "@/app/lib/utils";
import TimelineRow, { type TimelineEntry } from "./TimelineRow";

function buildTimeline(
    tasks: Task[],
    activities: Activity[],
    notes: Note[],
): TimelineEntry[] {
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
    ];
    return entries.sort((a, b) => b.sortAt - a.sortAt);
}

function entryAuthorId(entry: TimelineEntry): number | undefined {
    if (entry.kind === "task") return entry.task.assignedToId;
    if (entry.kind === "activity") return entry.activity.createdById;
    return entry.note.author;
}

export default async function Timeline({
    tasks,
    activities,
    notes,
    users = [],
    persons = [],
    deals = [],
    currentUserId,
    companyId,
    limit,
}: {
    tasks: Task[];
    activities: Activity[];
    notes: Note[];
    users?: User[];
    persons?: Contact[];
    deals?: Deal[];
    currentUserId?: number;
    companyId?: number | null;
    limit?: number;
}) {
    const t = await getTranslations("MeTimeline");
    const entries = buildTimeline(tasks, activities, notes);
    const visible = limit ? entries.slice(0, limit) : entries;

    if (visible.length === 0) {
        return (
            <p className="px-6 py-12 text-center text-sm text-muted-foreground">
                {t("emptyState")}
            </p>
        );
    }

    const userById = new Map(users.map((u) => [u.id, u]));

    return (
        <ul className="divide-y divide-border">
            {visible.map((entry) => {
                const authorId = entryAuthorId(entry);
                const author = authorId != null ? userById.get(authorId) : undefined;
                return (
                    <TimelineRow
                        key={`${entry.kind}-${
                            entry.kind === "task"
                                ? entry.task.id
                                : entry.kind === "activity"
                                    ? entry.activity.id
                                    : entry.note.id
                        }`}
                        entry={entry}
                        author={author}
                        persons={persons}
                        deals={deals}
                        currentUserId={currentUserId}
                        companyId={companyId ?? null}
                    />
                );
            })}
        </ul>
    );
}
