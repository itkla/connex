import { headers } from 'next/headers';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    FunnelIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';

import {
    getActivitiesFromCookie,
    getCompaniesFromCookie,
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getDealsFromCookie,
    getNotesFromCookie,
    getPipelinesFromCookie,
    getTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { User } from '@/app/lib/types';
import { timeOf } from '@/app/lib/utils';

import Greeting from '@/app/components/dashboard/Greeting';
import OverviewCard from '@/app/components/dashboard/OverviewCard';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import Rise from '@/app/components/dashboard/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import TaskSummary from '@/app/components/dashboard/TaskSummary';
import Timeline from '@/app/components/me/Timeline';

const DAY = 1000 * 60 * 60 * 24;

export default async function Dashboard() {
    const t = await getTranslations('DashboardPage');

    // TODO: move this somewhere else, or use the user object from layout.tsx
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [companies, contacts, deals, pipelines, tasks, activities, notes, users] =
        await Promise.all([
            getCompaniesFromCookie(cookie),
            getContactsFromCookie(cookie),
            getDealsFromCookie(cookie),
            getPipelinesFromCookie(cookie),
            getTasksFromCookie(cookie),
            getActivitiesFromCookie(cookie),
            getNotesFromCookie(cookie),
            getUsers(init).catch(() => [] as User[]),
        ]);

    // TODO: move this to it's own separate component so it can be reused elsewhere
    const now = new Date().getTime();
    const overdueTasks = tasks.filter((t) => {
        if (t.completed) return false;
        const due = timeOf(t.dueDate);
        return due > 0 && due < now;
    }).length;
    // TODO: use the function from @/app/components/dashboard/TaskSummary.tsx, or put it into the utils file and call it from both places idk just want to get this done rn
    const closingSoon = deals.filter((d) => {
        if (d.closedAt) return false;
        const t = timeOf(d.expectedCloseDate);
        return t > 0 && t - now <= 7 * DAY && t - now >= -DAY;
    }).length;
    const dueSoon = tasks.filter((tk) => {
        if (tk.completed) return false;
        const due = timeOf(tk.dueDate);
        return due >= now && due - now <= 7 * DAY;
    }).length;
    const upcomingActivities = activities.filter((a) => {
        const ts = timeOf(a.timestamp);
        return ts > now && ts - now <= 7 * DAY;
    }).length;

    return (
        <div className="min-h-screen bg-white px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise>
                    <Greeting
                        user={user}
                        overdueTasks={overdueTasks}
                        dueSoon={dueSoon}
                        closingSoon={closingSoon}
                        upcomingActivities={upcomingActivities}
                    />
                </Rise>

                <section>
                    <SectionHeader title={t('overview')} />
                    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                        <OverviewCard
                            index={0}
                            label={t('companies')}
                            value={companies.length}
                            icon={BuildingOffice2Icon}
                            href="/records/companies"
                            description={t('companiesDescription')}
                        />
                        <OverviewCard
                            index={1}
                            label={t('contacts')}
                            value={contacts.length}
                            icon={UsersIcon}
                            href="/records/contacts"
                        />
                        <OverviewCard
                            index={2}
                            label={t('deals')}
                            value={deals.length}
                            icon={BriefcaseIcon}
                            href="/records/deals"
                            description={t('dealsDescription')}
                        />
                        <OverviewCard
                            index={3}
                            label={t('pipelines')}
                            value={pipelines.length}
                            icon={FunnelIcon}
                            href="/records/pipelines"
                        />
                    </div>
                </section>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                    <Rise delay={0.24} className="flex flex-col">
                        <SectionHeader title={t('pipeline')} />
                        <PipelineChart deals={deals} />
                    </Rise>
                    <Rise delay={0.3} className="flex flex-col">
                        <SectionHeader title={t('tasks')} />
                        <TaskSummary tasks={tasks} />
                    </Rise>
                </div>

                <Rise delay={0.36}>
                    <section>
                        <SectionHeader
                            title={t('recentActivity')}
                            action={
                                <Link
                                    href="/activity/all"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewAll')}
                                </Link>
                            }
                        />
                        <div className="overflow-hidden rounded-2xl border border-black/[0.07] bg-white">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={users}
                                persons={contacts}
                                deals={deals}
                                currentUserId={user.id}
                                limit={8}
                            />
                        </div>
                    </section>
                </Rise>
            </div>
        </div>
    );
}