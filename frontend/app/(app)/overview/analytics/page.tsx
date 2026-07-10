import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getActivitiesFromCookie,
    getCompaniesFromCookie,
    getContactsFromCookie,
    getCompanyTemperaturesFromCookie,
    getContactTemperaturesFromCookie,
    getCurrentUserFromCookie,
    getDealMetricsFromCookie,
    getDealRisksFromCookie,
    getIntroSuggestionsFromCookie,
    getIntroductions,
    getNotesFromCookie,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getStagesByPipelineId,
    getTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type {
    Activity,
    Company,
    Contact,
    DealMetrics,
    DealRisk,
    IntroSuggestion,
    IntroductionRecord,
    JobMove,
    Note,
    Pipeline,
    RelationshipTemperature,
    Stage,
    Task,
    User,
} from '@/app/lib/types';
import AnalyticsBoard from '@/app/components/overview/analytics/AnalyticsBoard';

const EMPTY_DEAL_METRICS: DealMetrics = { byCurrency: [], totalCount: 0 };

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

    const [
        companies,
        contacts,
        dealMetrics,
        pipelines,
        tasks,
        activities,
        notes,
        users,
        dealRisks,
        introSuggestions,
        recentMoves,
        introLineage,
    ] = await Promise.all([
        getCompaniesFromCookie(cookie).catch(() => [] as Company[]),
        getContactsFromCookie(cookie).catch(() => [] as Contact[]),
        getDealMetricsFromCookie(cookie).catch(() => EMPTY_DEAL_METRICS),
        getPipelinesFromCookie(cookie).catch(() => [] as Pipeline[]),
        getTasksFromCookie(cookie).catch(() => [] as Task[]),
        getActivitiesFromCookie(cookie).catch(() => [] as Activity[]),
        getNotesFromCookie(cookie).catch(() => [] as Note[]),
        getUsers(init).catch(() => [] as User[]),
        getDealRisksFromCookie(cookie).catch(() => [] as DealRisk[]),
        getIntroSuggestionsFromCookie(cookie).catch(() => [] as IntroSuggestion[]),
        getRecentMovesFromCookie(cookie).catch(() => [] as JobMove[]),
        getIntroductions({ size: 500 }, init)
            .then((page) => page.items)
            .catch(() => [] as IntroductionRecord[]),
    ]);

    const [contactTemps, companyTemps] = await Promise.all([
        getContactTemperaturesFromCookie(cookie, contacts.map((contact) => contact.id)).catch(() => [] as RelationshipTemperature[]),
        getCompanyTemperaturesFromCookie(cookie, companies.map((company) => company.id)).catch(() => [] as RelationshipTemperature[]),
    ]);

    const stageLists = await Promise.all(
        pipelines.map((p) => getStagesByPipelineId(p.id, init).catch(() => [] as Stage[])),
    );
    const stages = stageLists.flat();

    return (
        <AnalyticsBoard
            dealMetrics={dealMetrics}
            pipelines={pipelines}
            stages={stages}
            activities={activities}
            tasks={tasks}
            notes={notes}
            users={users}
            contactTemps={contactTemps}
            companyTemps={companyTemps}
            dealRisks={dealRisks}
            introSuggestions={introSuggestions}
            introLineage={introLineage}
            recentMoves={recentMoves}
        />
    );
}
