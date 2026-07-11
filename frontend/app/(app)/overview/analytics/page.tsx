import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getCurrentUserFromCookie,
    getDealMetricsFromCookie,
    getDealRisksFromCookie,
    getIntroSuggestionsFromCookie,
    getIntroductions,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getStagesByPipelineId,
    getUsers,
} from '@/app/lib/api';
import type {
    DealMetrics,
    DealRisk,
    IntroSuggestion,
    IntroductionRecord,
    JobMove,
    Pipeline,
    Stage,
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
        dealMetrics,
        pipelines,
        users,
        dealRisks,
        introSuggestions,
        recentMoves,
        introLineage,
    ] = await Promise.all([
        getDealMetricsFromCookie(cookie).catch(() => EMPTY_DEAL_METRICS),
        getPipelinesFromCookie(cookie).catch(() => [] as Pipeline[]),
        getUsers(init).catch(() => [] as User[]),
        getDealRisksFromCookie(cookie).catch(() => [] as DealRisk[]),
        getIntroSuggestionsFromCookie(cookie).catch(() => [] as IntroSuggestion[]),
        getRecentMovesFromCookie(cookie).catch(() => [] as JobMove[]),
        getIntroductions({ size: 500 }, init)
            .then((page) => page.items)
            .catch(() => [] as IntroductionRecord[]),
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
            users={users}
            dealRisks={dealRisks}
            introSuggestions={introSuggestions}
            introLineage={introLineage}
            recentMoves={recentMoves}
        />
    );
}
