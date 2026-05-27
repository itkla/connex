import Link from 'next/link';
import { getTranslations } from 'next-intl/server';
import { type Task } from '@/app/lib/api';
import { formatShortDate, timeOf } from '@/app/lib/utils';

const DAY = 1000 * 60 * 60 * 24;

export default async function TaskSummary({ tasks }: { tasks: Task[] }) {
    const t = await getTranslations('DashboardTaskSummary');
    const open = tasks.filter((tk) => !tk.completed);
    const now = Date.now();

    // items that are > due and < now (e.g. overdue from today)
    const overdue = open.filter((tk) => {
        const due = timeOf(tk.dueDate);
        return due > 0 && due < now;
    }).length;

    // items that are >= now and < now + 7 days (e.g. due this week)
    const dueSoon = open.filter((tk) => {
        const due = timeOf(tk.dueDate);
        return due >= now && due - now <= 7 * DAY;
    }).length;

    // items that are > now
    const upcoming = [...open]
        .sort((a, b) => {
            const aDue = timeOf(a.dueDate) || Infinity;
            const bDue = timeOf(b.dueDate) || Infinity;
            return aDue - bDue;
        })
        .slice(0, 4);

    return (
        <div className="flex h-full flex-col rounded-2xl bg-white p-6 ring-1 ring-black/5">
            <div className="flex items-baseline justify-between">
                <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                    {t('openTasks')}
                </span>
                <Link
                    href="/activity/tasks"
                    className="text-xs text-brand hover:text-brand-hover"
                >
                    {t('viewAll')}
                </Link>
            </div>
            <span className="mt-3 text-5xl leading-none text-black tabular-nums">
                {open.length}
            </span>
            <p className="mt-2 text-sm text-neutral-500">
                {overdue > 0 ? (
                    <span className="text-red-600 font-medium">
                        {t('overdueCount', { count: overdue })}
                    </span>
                ) : (
                    <span>{t('overdueCount', { count: 0 })}</span>
                )}
                {t('separator')}
                {t('dueThisWeek', { count: dueSoon })}
            </p>
            {upcoming.length > 0 ? (
                <ul className="mt-6 divide-y divide-neutral-200 border-t border-neutral-200">
                    {upcoming.map((task) => {
                        const due = timeOf(task.dueDate);
                        const isOverdue = due > 0 && due < now;
                        return (
                            <li
                                key={task.id}
                                className="flex items-center justify-between gap-3 py-3"
                            >
                                <span className="line-clamp-1 text-sm text-black">
                                    {task.description}
                                </span>
                                {task.dueDate ? (
                                    <span
                                        className={`shrink-0 text-xs ${
                                            isOverdue
                                                ? 'text-red-600 font-medium'
                                                : 'text-neutral-500'
                                        }`}
                                    >
                                        {formatShortDate(task.dueDate)}
                                    </span>
                                ) : (
                                    <span className="shrink-0 text-xs text-neutral-400">
                                        {t('noDate')}
                                    </span>
                                )}
                            </li>
                        );
                    })}
                </ul>
            ) : (
                <p className="mt-6 border-t border-neutral-200 pt-6 text-sm text-neutral-500">
                    {t('allCaughtUp')}
                </p>
            )}
        </div>
    );
}