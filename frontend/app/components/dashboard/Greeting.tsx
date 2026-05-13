import { type User } from '@/app/lib/api';

function timeOfDayGreeting(): string {
    const h = new Date().getHours();
    if (h < 5) return 'Working late,';
    if (h < 12) return 'Good morning,';
    if (h < 17) return 'Good afternoon,';
    if (h < 21) return 'Good evening,';
    return 'Working late,';
}

function todayLabel(): string {
    return new Intl.DateTimeFormat('en', {
        weekday: 'long',
        month: 'long',
        day: 'numeric',
    }).format(new Date());
}

export default function Greeting({
    user,
    overdueTasks,
    closingSoon,
}: {
    user: User;
    overdueTasks: number;
    closingSoon: number;
}) {
    const firstName = user.displayName?.split(' ')[0] ?? user.displayName;

    let callout: React.ReactNode = (
        <span className="text-neutral-500">
            Nothing urgent today. Smooth sailing.
        </span>
    );
    if (overdueTasks > 0) {
        callout = (
            <span className="text-neutral-700">
                <span className="text-red-600 font-medium">
                    {overdueTasks} task{overdueTasks === 1 ? '' : 's'} overdue
                </span>
                {closingSoon > 0 ? (
                    <span className="text-neutral-500">
                        {' '}· {closingSoon} deal{closingSoon === 1 ? '' : 's'} closing this week
                    </span>
                ) : null}
            </span>
        );
    } else if (closingSoon > 0) {
        callout = (
            <span className="text-neutral-700">
                <span className="text-brand-dark font-medium">
                    {closingSoon} deal{closingSoon === 1 ? '' : 's'}
                </span>
                <span className="text-neutral-500"> closing this week</span>
            </span>
        );
    }

    return (
        <header className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
            <h1 className="leading-tight tracking-tight">
                <span className="block text-2xl font-medium text-neutral-500">
                    {timeOfDayGreeting()}
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