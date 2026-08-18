import { getLocale, getTranslations } from "next-intl/server";
import { CheckCircleIcon } from "@heroicons/react/24/outline";

import { type Task } from "@/app/lib/types";
import { EmptyState } from "@/app/components/EmptyState";
import NoteContent from "@/app/components/activity/notes/NoteContent";
import { timeOf, formatShortDate } from "@/app/lib/utils";

export default async function TaskList({ tasks }: { tasks: Task[] }) {
    const t = await getTranslations("MeTaskList");
    const locale = await getLocale();

    if (tasks.length === 0) {
        return (
            <EmptyState
                variant="inline"
                tone="muted"
                icon={CheckCircleIcon}
                title={t("emptyTitle")}
                body={t("empty")}
            />
        );
    }

    // sort by completed status, then by due date, then by created at
    const sorted = [...tasks].sort((a, b) => {
        if (a.completed !== b.completed) return a.completed ? 1 : -1; // sort by completed status
        const aDue = timeOf(a.dueDate);
        const bDue = timeOf(b.dueDate);
        if (aDue && bDue) return aDue - bDue; // sort by due date 
        if (aDue) return -1; // if a has a due date and b does not, a should come first
        if (bDue) return 1; // if b has a due date and a does not, b should come first
        return timeOf(b.createdAt) - timeOf(a.createdAt);
    });

    const recent = sorted.slice(0, 5);

    return (
        <ul className="divide-y divide-border">
            {recent.map((task) => (
                <li
                    key={task.id}
                    className="flex items-start justify-between gap-4 px-6 py-3"
                >
                    <span
                        className={`text-sm ${task.completed ? 'text-muted-foreground line-through' : 'text-foreground'}`}
                    >
                        <NoteContent content={task.description} references={task.references} />
                    </span>
                    {task.dueDate ? (
                        <span className="shrink-0 text-xs text-muted-foreground">
                            {formatShortDate(task.dueDate, locale)}
                        </span>
                    ) : null}
                </li>
            ))}
        </ul>
    );
}