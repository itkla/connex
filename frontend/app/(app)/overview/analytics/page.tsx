import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getActivitiesFromCookie,
    getCompaniesFromCookie,
    getCurrentUserFromCookie,
    getDealsFromCookie,
    getNotesFromCookie,
    getPipelinesFromCookie,
    getStagesByPipelineId,
    getTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Activity, Company, Deal, Note, Pipeline, Stage, Task, User } from '@/app/lib/types';
import AnalyticsBoard from '@/app/components/overview/analytics/AnalyticsBoard';

export const metadata: Metadata = {
    title: 'Analytics',
    description: 'Revenue, pipeline, and team activity at a glance',
};

export default async function AnalyticsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;

    const [companies, deals, pipelines, tasks, activities, notes, users] = await Promise.all([
        getCompaniesFromCookie(cookie).catch(() => [] as Company[]),
        getDealsFromCookie(cookie).catch(() => [] as Deal[]),
        getPipelinesFromCookie(cookie).catch(() => [] as Pipeline[]),
        getTasksFromCookie(cookie).catch(() => [] as Task[]),
        getActivitiesFromCookie(cookie).catch(() => [] as Activity[]),
        getNotesFromCookie(cookie).catch(() => [] as Note[]),
        getUsers(init).catch(() => [] as User[]),
    ]);

    const stageLists = await Promise.all(
        pipelines.map((p) => getStagesByPipelineId(p.id, init).catch(() => [] as Stage[])),
    );
    const stages = stageLists.flat();

    return (
        <AnalyticsBoard
            deals={deals}
            companies={companies}
            pipelines={pipelines}
            stages={stages}
            activities={activities}
            tasks={tasks}
            notes={notes}
            users={users}
        />
    );
}