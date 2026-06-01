// NOTE: not used in /me page anymore, but might be used in other pages so im keeping it

import { getLocale, getTranslations } from "next-intl/server";

import { type Task } from "@/app/lib/types";
import EmptyState from "./EmptyState";
import { timeOf, formatShortDate } from "@/app/lib/utils";

export default async function TaskList({ tasks }: { tasks: Task[] }) {
    const t = await getTranslations("MeTaskList");
    const locale = await getLocale();

    if (tasks.length === 0) {
        return <EmptyState message={t("empty")} />;
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
        <ul className="divide-y divide-neutral-200">
            {recent.map((task) => (
                <li
                    key={task.id}
                    className="flex items-start justify-between gap-4 px-6 py-3"
                >
                    <span
                        className={`text-sm ${task.completed ? 'text-neutral-400 line-through' : 'text-black'}`}
                    >
                        {task.description}
                    </span>
                    {task.dueDate ? (
                        <span className="shrink-0 text-xs text-neutral-500">
                            {formatShortDate(task.dueDate, locale)}
                        </span>
                    ) : null}
                </li>
            ))}
        </ul>
    );
}