import { getTranslations } from 'next-intl/server';
import { type User } from '@/app/lib/types';

function timeOfDayGreetingKey(): 'workingLate' | 'goodMorning' | 'goodAfternoon' | 'goodEvening' {
    const h = new Date().getHours();
    if (h < 5) return 'workingLate';
    if (h < 12) return 'goodMorning';
    if (h < 17) return 'goodAfternoon';
    if (h < 21) return 'goodEvening';
    return 'workingLate';
}

function todayLabel(): string {
    return new Intl.DateTimeFormat('en', {
        weekday: 'long',
        month: 'long',
        day: 'numeric',
    }).format(new Date());
}

export default async function Greeting({
    user,
    overdueTasks,
    closingSoon,
}: {
    user: User;
    overdueTasks: number;
    closingSoon: number;
}) {
    const t = await getTranslations('DashboardGreeting');
    const firstName = user.displayName?.split(' ')[0] ?? user.displayName;

    let callout: React.ReactNode = (
        <span className="text-neutral-500">
            {t('nothingUrgent')}
        </span>
    );
    if (overdueTasks > 0) {
        callout = (
            <span className="text-neutral-700">
                <span className="text-red-600 font-medium">
                    {t('tasksOverdue', { count: overdueTasks })}
                </span>
                {closingSoon > 0 ? (
                    <span className="text-neutral-500">
                        {t('dealsClosingSeparator', { count: closingSoon })}
                    </span>
                ) : null}
            </span>
        );
    } else if (closingSoon > 0) {
        callout = (
            <span className="text-neutral-700">
                <span className="text-brand-dark font-medium">
                    {t('dealsCount', { count: closingSoon })}
                </span>
                <span className="text-neutral-500">{t('closingThisWeek')}</span>
            </span>
        );
    }

    return (
        <header className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
            <h1 className="leading-tight tracking-tight">
                <span className="block text-2xl font-medium text-neutral-500">
                    {t(timeOfDayGreetingKey())}
                </span>
                <span className="mt-1 block text-4xl font-extrabold tracking-tight text-black md:text-5xl">
                    {firstName}
                </span>
                <span className="mt-3 block text-sm">{callout}</span>
            </h1>
            <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {todayLabel()}
            </span>
        </header>
    );
}
