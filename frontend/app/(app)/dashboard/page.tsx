import { headers } from 'next/headers';
import Link from 'next/link';
import { redirect } from 'next/navigation';
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
} from '@/app/lib/api';
import { timeOf } from '@/app/lib/utils';

import Greeting from '@/app/components/dashboard/Greeting';
import OverviewCard from '@/app/components/dashboard/OverviewCard';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import TaskSummary from '@/app/components/dashboard/TaskSummary';
import Timeline from '@/app/components/me/Timeline';

const DAY = 1000 * 60 * 60 * 24;

export default async function Dashboard() {

    // TODO: move this somewhere else, or use the user object from layout.tsx
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const [companies, contacts, deals, pipelines, tasks, activities, notes] =
        await Promise.all([
            getCompaniesFromCookie(cookie),
            getContactsFromCookie(cookie),
            getDealsFromCookie(cookie),
            getPipelinesFromCookie(cookie),
            getTasksFromCookie(cookie),
            getActivitiesFromCookie(cookie),
            getNotesFromCookie(cookie),
        ]);

    // TODO: move this to it's own separate component so it can be reused elsewhere
    const now = Date.now();
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

    return (
        <div className="min-h-screen bg-white px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Greeting
                    user={user}
                    overdueTasks={overdueTasks}
                    closingSoon={closingSoon}
                />

                <section>
                    <SectionHeader title="Overview" />
                    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                        <OverviewCard
                            label="Companies"
                            value={companies.length}
                            icon={BuildingOffice2Icon}
                            href="/records/companies"
                            description="You're up +1 company from last week"
                        />
                        <OverviewCard
                            label="Contacts"
                            value={contacts.length}
                            icon={UsersIcon}
                            href="/records/contacts"
                        />
                        <OverviewCard
                            label="Deals"
                            value={deals.length}
                            icon={BriefcaseIcon}
                            href="/records/deals"
                            description="The last deal closed for $100,000"
                        />
                        <OverviewCard
                            label="Pipelines"
                            value={pipelines.length}
                            icon={FunnelIcon}
                            href="/records/pipelines"
                        />
                    </div>
                </section>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                    <section className="flex flex-col">
                        <SectionHeader title="Pipeline" />
                        <PipelineChart deals={deals} />
                    </section>
                    <section className="flex flex-col">
                        <SectionHeader title="Tasks" />
                        <TaskSummary tasks={tasks} />
                    </section>
                </div>

                <section>
                    <SectionHeader
                        title="Recent activity"
                        action={
                            <Link
                                href="/activity"
                                className="text-xs text-brand hover:text-brand-hover"
                            >
                                View all
                            </Link>
                        }
                    />
                    <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5">
                        <Timeline
                            tasks={tasks}
                            activities={activities}
                            notes={notes}
                            limit={8}
                        />
                    </div>
                </section>
            </div>
        </div>
    );
}