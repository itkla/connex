import Link from 'next/link';
import { ExclamationTriangleIcon } from '@heroicons/react/16/solid';
import { getLocale, getTranslations } from 'next-intl/server';
import { type Task, type TaskSummary as TaskSummaryCounts } from '@/app/lib/types';
import CountUp from '@/app/components/dashboard/CountUp';
import UpcomingTasks from '@/app/components/dashboard/UpcomingTasks';
import { Badge } from '@/components/ui/badge';

export default async function TaskSummary({
    summary,
    upcoming,
}: {
    summary: TaskSummaryCounts;
    upcoming: Task[];
}) {
    const t = await getTranslations('DashboardTaskSummary');
    const locale = await getLocale();
    const open = summary.todo + summary.inProgress;

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
                <CountUp value={open} className="text-5xl leading-none text-foreground tabular-nums" />
                {summary.overdue > 0 ? (
                    <Badge variant="destructive" className="gap-1 px-2.5">
                        <ExclamationTriangleIcon />
                        {t('overdueCount', { count: summary.overdue })}
                    </Badge>
                ) : null}
            </div>
            <p className="mt-2 text-sm text-muted-foreground">{t('dueThisWeek', { count: summary.dueSoon })}</p>
            <UpcomingTasks tasks={upcoming} locale={locale} />
        </div>
    );
}
