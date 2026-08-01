import { getLocale, getTranslations } from 'next-intl/server';
import { BellAlertIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { type User } from '@/app/lib/types';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';

function timeOfDayGreetingKey(): 'workingLate' | 'goodMorning' | 'goodAfternoon' | 'goodEvening' {
    const h = new Date().getHours();
    if (h < 5) return 'workingLate';
    if (h < 12) return 'goodMorning';
    if (h < 17) return 'goodAfternoon';
    if (h < 21) return 'goodEvening';
    return 'workingLate';
}

function todayLabel(locale: string): string {
    return new Intl.DateTimeFormat(locale, {
        weekday: 'long',
        month: 'long',
        day: 'numeric',
    }).format(new Date());
}

export default async function Greeting({
    user,
    overdueTasks,
    dueSoon,
    closingSoon,
    upcomingActivities,
    action,
    signalsUnavailable = false,
}: {
    user: User;
    overdueTasks: number;
    dueSoon: number;
    closingSoon: number;
    upcomingActivities: number;
    action?: React.ReactNode;
    /**
     * Set when the counts behind the day's summary could not be loaded. The banner then says so
     * rather than reporting "nothing urgent", which would be a false all-clear when there may be
     * overdue work we simply could not count.
     */
    signalsUnavailable?: boolean;
}) {
    const t = await getTranslations('DashboardGreeting');
    const locale = await getLocale();
    const firstName = user.displayName?.split(' ')[0] ?? user.displayName;

    const parts: string[] = [];
    if (dueSoon > 0) parts.push(t('tasksDue', { count: dueSoon }));
    if (closingSoon > 0) parts.push(t('dealsClosing', { count: closingSoon }));
    if (upcomingActivities > 0) parts.push(t('activitiesUpcoming', { count: upcomingActivities }));
    const upcomingSummary = parts.join(' · ');
    const hasUpcoming = parts.length > 0;

    let status: React.ReactNode;
    if (signalsUnavailable) {
        status = <p className="text-sm text-muted-foreground">{t('summaryUnavailable')}</p>;
    } else if (overdueTasks > 0) {
        status = (
            <Alert variant="destructive" className="w-fit max-w-md border-destructive/20 bg-destructive/5">
                <ExclamationTriangleIcon />
                <AlertTitle>{t('tasksOverdue', { count: overdueTasks })}</AlertTitle>
                {hasUpcoming ? <AlertDescription>{upcomingSummary}</AlertDescription> : null}
            </Alert>
        );
    } else if (hasUpcoming) {
        status = (
            <Alert className="w-fit max-w-md border-brand/30 bg-brand-light/40">
                <BellAlertIcon />
                <AlertTitle>{t('upcomingTitle')}</AlertTitle>
                <AlertDescription className="text-muted-foreground">{upcomingSummary}</AlertDescription>
            </Alert>
        );
    } else {
        status = <p className="text-sm text-muted-foreground">{t('nothingUrgent')}</p>;
    }

    return (
        <header className="flex flex-col gap-5 md:flex-row md:items-center md:justify-between md:gap-8">
            <div className="min-w-0">
                <h1 className="leading-tight tracking-tight">
                    <span className="block text-2xl font-medium text-muted-foreground">
                        {t(timeOfDayGreetingKey())}
                    </span>
                    <span className="mt-1 block text-4xl font-extrabold tracking-tight text-foreground md:text-5xl">
                        {firstName}
                    </span>
                </h1>
                <span className="mt-3 block text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                    {todayLabel(locale)}
                </span>
            </div>
            <div className="flex shrink-0 flex-col items-start gap-4 md:items-end">
                {action}
                {status}
            </div>
        </header>
    );
}
