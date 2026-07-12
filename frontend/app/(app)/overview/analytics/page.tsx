import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

import {
    getCurrentUserFromCookie,
    getDealMetricsFromCookie,
    getDealRiskAnalyticsFromCookie,
    getIntroSuggestionsFromCookie,
    getIntroductions,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getStagesByPipelineId,
    getTaskSummaryFromCookie,
    getUsers,
    getWarmthSummaryFromCookie,
} from '@/app/lib/api';
import type {
    DealMetrics,
    DealRiskAnalytics,
    IntroSuggestion,
    IntroductionRecord,
    JobMove,
    Pipeline,
    Stage,
    TaskSummary,
    User,
    WarmthSummary,
} from '@/app/lib/types';
import AnalyticsBoard from '@/app/components/overview/analytics/AnalyticsBoard';

const EMPTY_DEAL_METRICS: DealMetrics = { byCurrency: [], totalCount: 0 };
const EMPTY_DEAL_RISK_ANALYTICS: DealRiskAnalytics = { currencies: [], truncated: false };
const EMPTY_TASK_SUMMARY: TaskSummary = { todo: 0, inProgress: 0, done: 0, overdue: 0, dueSoon: 0 };
const EMPTY_WARMTH_SUMMARY: WarmthSummary = {
    contacts: { hot: 0, warm: 0, cool: 0, cold: 0 },
    companies: { hot: 0, warm: 0, cool: 0, cold: 0 },
    contactTrends: { rising: 0, steady: 0, cooling: 0 },
    contactDecay: { soon: 0, mid: 0, later: 0 },
};

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
        dealRiskAnalytics,
        introSuggestions,
        recentMoves,
        introLineage,
        taskSummary,
        warmth,
    ] = await Promise.all([
        getDealMetricsFromCookie(cookie).catch(() => EMPTY_DEAL_METRICS),
        getPipelinesFromCookie(cookie).catch(() => [] as Pipeline[]),
        getUsers(init).catch(() => [] as User[]),
        getDealRiskAnalyticsFromCookie(cookie).catch(() => EMPTY_DEAL_RISK_ANALYTICS),
        getIntroSuggestionsFromCookie(cookie).catch(() => [] as IntroSuggestion[]),
        getRecentMovesFromCookie(cookie).catch(() => [] as JobMove[]),
        getIntroductions({ size: 500 }, init)
            .then((page) => page.items)
            .catch(() => [] as IntroductionRecord[]),
        getTaskSummaryFromCookie(cookie).catch(() => EMPTY_TASK_SUMMARY),
        getWarmthSummaryFromCookie(cookie).catch(() => EMPTY_WARMTH_SUMMARY),
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
            dealRiskAnalytics={dealRiskAnalytics}
            introSuggestions={introSuggestions}
            introLineage={introLineage}
            recentMoves={recentMoves}
            taskSummary={taskSummary}
            warmth={warmth}
        />
    );
}
