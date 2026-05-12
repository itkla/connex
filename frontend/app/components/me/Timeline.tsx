import { type Activity, type Note, type Task } from "@/app/lib/api";
import { formatShortDate, timeOf } from "@/app/lib/utils";
import { CheckIcon } from "@heroicons/react/24/outline";

type TimelineEntry =
    | { kind: "task"; sortAt: number; task: Task }
    | { kind: "activity"; sortAt: number; activity: Activity }
    | { kind: "note"; sortAt: number; note: Note };

function buildTimeline(
    tasks: Task[],
    activities: Activity[],
    notes: Note[],
): TimelineEntry[] {
    const entries: TimelineEntry[] = [
        ...tasks.map<TimelineEntry>((task) => ({ // sort by updated at, then by created at
            kind: "task",
            sortAt: timeOf(task.updatedAt) || timeOf(task.createdAt),
            task,
        })),
        ...activities.map<TimelineEntry>((activity) => ({ // sort by timestamp
            kind: "activity",
            sortAt: timeOf(activity.timestamp),
            activity,
        })),
        ...notes.map<TimelineEntry>((note) => ({ // sort by updated at, then by created at
            kind: "note",
            sortAt: timeOf(note.updatedAt) || timeOf(note.createdAt),
            note,
        })),
    ];
    return entries.sort((a, b) => b.sortAt - a.sortAt);
}

const CHIP_CLASS: Record<TimelineEntry["kind"], string> = {
    task: "bg-brand-light text-brand-dark", // lightish green as defined in global.scss
    activity: "bg-neutral-900 text-white",
    note: "bg-neutral-200 text-neutral-700",
};

// used to render the type chip
function TypeChip({ kind }: { kind: TimelineEntry["kind"] }) {
    return (
        <span className={`inline-flex items-center rounded-md px-2 py-1 text-xs font-medium inset-ring ${CHIP_CLASS[kind]}`}>
            <span className="mr-1">●</span>{kind}
        </span>
    );
}

// used to key the timeline entries
function entryKey(entry: TimelineEntry): string {
    if (entry.kind === "task") return `task-${entry.task.id}`;
    if (entry.kind === "activity") return `activity-${entry.activity.id}`;
    return `note-${entry.note.id}`;
}

// used to get the date of the timeline entry
function entryDate(entry: TimelineEntry): string {
    return entry.sortAt
        ? formatShortDate(new Date(entry.sortAt).toISOString())
        : "";
}

// used to render the timeline entry
function TimelineRow({ entry }: { entry: TimelineEntry }) {
    const date = entryDate(entry);

    let title: React.ReactNode;
    let subtitle: React.ReactNode = null;

    if (entry.kind === "task") {
        const { task } = entry;
        title = (
            <p className={`text-sm ${task.completed ? "text-neutral-400 line-through" : "text-black"}`}>
                {task.description}
            </p>
        );
        if (task.completed) {
            subtitle = (
                <span className="flex items-center gap-2">
                    <CheckIcon className="h-4 w-4 text-neutral-500" />
                    <p className="text-xs text-neutral-500">Completed</p>
                </span>
            );
        } else if (task.dueDate) {
            subtitle = (
                <p className="mt-0.5 text-xs text-neutral-500">
                    Due {formatShortDate(task.dueDate)}
                </p>
            );
        }
    } else if (entry.kind === "activity") {
        const { activity } = entry;
        title = <p className="text-sm text-black">{activity.subject}</p>;
        subtitle = (
            <div className="mt-0.5 flex min-w-0 items-center gap-2">
                <span className="text-xs font-medium tracking-wide text-neutral-500 uppercase">
                    {activity.type}
                </span>
                {activity.notes ? (
                    <span className="truncate text-xs text-neutral-500">
                        · {activity.notes}
                    </span>
                ) : null}
            </div>
        );
    } else {
        title = (
            <p className="line-clamp-2 text-sm text-black">
                {entry.note.content}
            </p>
        );
    }

    return (
        <li className="flex items-start gap-4 px-6 py-4">
            <TypeChip kind={entry.kind} />
            <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-3">
                    {title}
                    {date ? (
                        <time className="shrink-0 text-xs text-neutral-500">
                            {date}
                        </time>
                    ) : null}
                </div>
                {subtitle}
            </div>
        </li>
    );
}

export default function Timeline({
    tasks,
    activities,
    notes,
}: {
    tasks: Task[];
    activities: Activity[];
    notes: Note[];
}) {
    const entries = buildTimeline(tasks, activities, notes);

    if (entries.length === 0) {
        return (
            <p className="px-6 py-12 text-center text-sm text-neutral-500">
                Nothing recent yet.
            </p>
        );
    }

    return (
        <ul className="divide-y divide-neutral-200">
            {entries.map((entry) => (
                <TimelineRow key={entryKey(entry)} entry={entry} />
            ))}
        </ul>
    );
}
