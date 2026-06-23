import Link from 'next/link';
import { ExclamationTriangleIcon } from '@heroicons/react/16/solid';
import { getLocale, getTranslations } from 'next-intl/server';
import { type Task } from '@/app/lib/types';
import { startOfLocalDay, timeOf } from '@/app/lib/utils';
import CountUp from '@/app/components/dashboard/CountUp';
import UpcomingTasks from '@/app/components/dashboard/UpcomingTasks';
import { Badge } from '@/components/ui/badge';

const DAY = 1000 * 60 * 60 * 24;

export default async function TaskSummary({ tasks }: { tasks: Task[] }) {
    const t = await getTranslations('DashboardTaskSummary');
    const locale = await getLocale();
    const open = tasks.filter((tk) => !tk.completed);
    const now = new Date().getTime();
    const todayStart = startOfLocalDay(now);

    // items that are > due and < now (e.g. overdue from today)
    const overdue = open.filter((tk) => {
        const due = timeOf(tk.dueDate);
        return due > 0 && due < todayStart;
    }).length;

    // items that are >= now and < now + 7 days (e.g. due this week)
    const dueSoon = open.filter((tk) => {
        const due = timeOf(tk.dueDate);
        return due >= todayStart && due - todayStart <= 7 * DAY;
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
        <div className="flex h-full flex-col rounded-2xl border border-border bg-card p-6">
            <div className="flex items-baseline justify-between">
                <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                    {t('openTasks')}
                </span>
                <Link
                    href="/activity/tasks"
                    className="text-xs text-brand hover:text-brand-hover"
                >
                    {t('viewAll')}
                </Link>
            </div>
            <div className="mt-3 flex items-center gap-3">
                <CountUp value={open.length} className="text-5xl leading-none text-foreground tabular-nums" />
                {overdue > 0 ? (
                    <Badge variant="destructive" className="gap-1 px-2.5">
                        <ExclamationTriangleIcon />
                        {t('overdueCount', { count: overdue })}
                    </Badge>
                ) : null}
            </div>
            <p className="mt-2 text-sm text-muted-foreground">{t('dueThisWeek', { count: dueSoon })}</p>
            <UpcomingTasks tasks={upcoming} locale={locale} />
        </div>
    );
}