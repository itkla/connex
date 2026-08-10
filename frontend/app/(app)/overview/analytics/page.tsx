import type { Metadata } from 'next';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import {
    getAllStagesFromCookie,
    getCurrentUserResultFromCookie,
    getDealMetricsFromCookie,
    getDealRiskAnalyticsFromCookie,
    getIntroSuggestionsFromCookie,
    getIntroductions,
    getMyWorkspacesFromCookie,
    getPipelinesFromCookie,
    getRecentMovesResultFromCookie,
    getTaskSummaryFromCookie,
    getUsers,
    getWarmthSummaryFromCookie,
} from '@/app/lib/api';
import type {
    DealMetrics,
    DealRiskAnalytics,
    IntroSuggestion,
    IntroductionRecord,
    Pipeline,
    TaskSummary,
    User,
    WarmthSummary,
} from '@/app/lib/types';
import { resolveWorkspaceTimezone } from '@/app/lib/workspaceSnapshot';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
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

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations('AnalyticsLayout');
    return {
        title: t('title'),
        description: t('description'),
    };
}

export default async function AnalyticsPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }
    const workspaceSnapshot = await getMyWorkspacesFromCookie(cookie);
    const timezone = resolveWorkspaceTimezone(workspaceSnapshot, user.timezone);

    const init = { headers: { cookie: cookie ?? '' } } as const;

    const [
        dealMetrics,
        pipelines,
        stages,
        users,
        dealRiskAnalytics,
        introSuggestions,
        recentMovesResult,
        introLineage,
        taskSummary,
        warmth,
    ] = await Promise.all([
        getDealMetricsFromCookie(cookie).catch(() => EMPTY_DEAL_METRICS),
        getPipelinesFromCookie(cookie).catch(() => [] as Pipeline[]),
        getAllStagesFromCookie(cookie),
        getUsers(init).catch(() => [] as User[]),
        getDealRiskAnalyticsFromCookie(cookie).catch(() => EMPTY_DEAL_RISK_ANALYTICS),
        getIntroSuggestionsFromCookie(cookie).catch(() => [] as IntroSuggestion[]),
        getRecentMovesResultFromCookie(cookie),
        getIntroductions({ size: 500 }, init)
            .then((page) => page.items)
            .catch(() => [] as IntroductionRecord[]),
        getTaskSummaryFromCookie(cookie).catch(() => EMPTY_TASK_SUMMARY),
        getWarmthSummaryFromCookie(cookie).catch(() => EMPTY_WARMTH_SUMMARY),
    ]);
    const recentMoves = recentMovesResult.ok ? recentMovesResult.data : [];

    return (
        <AnalyticsBoard
            dealMetrics={dealMetrics}
            timezone={timezone}
            pipelines={pipelines}
            stages={stages}
            users={users}
            dealRiskAnalytics={dealRiskAnalytics}
            introSuggestions={introSuggestions}
            introLineage={introLineage}
            recentMoves={recentMoves}
            recentMovesAvailable={recentMovesResult.ok}
            taskSummary={taskSummary}
            warmth={warmth}
        />
    );
}
