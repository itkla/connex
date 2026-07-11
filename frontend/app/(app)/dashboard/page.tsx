import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    FunnelIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';

import {
    getActivitiesFromCookie,
    getActivityVolumeFromCookie,
    getAttachmentFacets,
    getAttachmentsPage,
    getCompanyById,
    getCompaniesPage,
    getCoolingCompanyTemperaturesFromCookie,
    getCoolingContactTemperaturesFromCookie,
    getContactById,
    getContactsFromCookie,
    getContactsPage,
    getCurrentUserFromCookie,
    getDashboardLayoutFromCookie,
    getDealById,
    getDealClosingSoonFromCookie,
    getDealClosingSoonCountFromCookie,
    getDealKpisFromCookie,
    getDealMetricsFromCookie,
    getDealPipelineValueFromCookie,
    getDealRevenueTimeseries,
    getDealRisksFromCookie,
    getDealStageDistribution,
    getDealsFromCookie,
    getIntroSuggestionsFromCookie,
    getNotesFromCookie,
    getNotifications,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getStagesByPipelineId,
    getTaskSummaryFromCookie,
    getTasksFromCookie,
    getTeamLeaderboardFromCookie,
    getUpcomingActivityCountFromCookie,
    getUsers,
    getWarmthSummaryFromCookie,
} from '@/app/lib/api';
import type {
    ActivityVolumeBucket,
    Attachment,
    AttachmentFacets,
    Company,
    Contact,
    Count,
    DashboardWidgetType,
    Deal,
    DealKpis,
    DealMetrics,
    DealPipelineValue,
    DealRevenueSeries,
    DealStageDistribution,
    Notification,
    Page,
    RelationshipTemperature,
    Stage,
    TaskSummary as TaskSummaryCounts,
    TeamLeaderboardEntry,
    User,
    WarmthSummary,
} from '@/app/lib/types';

import AtRiskDeals, { type AtRiskItem } from '@/app/components/dashboard/AtRiskDeals';
import CoolingRelationships, { type CoolingItem } from '@/app/components/dashboard/CoolingRelationships';
import Greeting from '@/app/components/dashboard/Greeting';
import IntroOpportunities from '@/app/components/dashboard/IntroOpportunities';
import OverviewCard from '@/app/components/dashboard/OverviewCard';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import RecentFiles from '@/app/components/dashboard/RecentFiles';
import RecentMoves from '@/app/components/dashboard/RecentMoves';
import Rise from '@/app/components/motion/Rise';
import TaskSummary from '@/app/components/dashboard/TaskSummary';
import Timeline from '@/app/components/me/Timeline';
import DashboardGrid from '@/app/components/dashboard/customize/DashboardGrid';
import { normalizeLayout } from '@/app/components/dashboard/customize/dashboardWidgets';
import CompanyWarmth, { type CompanyWarmthItem } from '@/app/components/dashboard/CompanyWarmth';
import WarmthDistribution from '@/app/components/dashboard/WarmthDistribution';
import ClosingSoonDeals, { type ClosingSoonItem } from '@/app/components/dashboard/ClosingSoonDeals';
import NotificationsCard from '@/app/components/dashboard/NotificationsCard';
import AnalyticsKpisWidget from '@/app/components/dashboard/AnalyticsKpisWidget';
import QuickCreate from '@/app/components/dashboard/QuickCreate';
import NoteList from '@/app/components/me/NoteList';
import RevenueTrend from '@/app/components/overview/analytics/RevenueTrend';
import WinRateDonut from '@/app/components/overview/analytics/WinRateDonut';
import PipelineValue from '@/app/components/overview/analytics/PipelineValue';
import StageFunnel from '@/app/components/overview/analytics/StageFunnel';
import ActivityVolume from '@/app/components/overview/analytics/ActivityVolume';
import TeamLeaderboard from '@/app/components/overview/analytics/TeamLeaderboard';
import type { RangeKey } from '@/app/components/overview/analytics/metrics';

const EMPTY_DEAL_KPIS: DealKpis = {
    wonRevenue: 0,
    wonRevenuePrev: null,
    newPipeline: 0,
    newPipelinePrev: null,
    wonCount: 0,
    lostCount: 0,
    wonValue: 0,
    lostValue: 0,
    wonCountPrev: null,
    lostCountPrev: null,
    avgCycleDays: 0,
    avgCycleDaysPrev: null,
    wonSeries: [],
    newPipelineSeries: [],
    winRateSeries: [],
    avgCycleSeries: [],
};

const EMPTY_TASK_SUMMARY: TaskSummaryCounts = {
    todo: 0,
    inProgress: 0,
    done: 0,
    overdue: 0,
    dueSoon: 0,
};

const EMPTY_WARMTH_SUMMARY: WarmthSummary = {
    contacts: { hot: 0, warm: 0, cool: 0, cold: 0 },
    companies: { hot: 0, warm: 0, cool: 0, cold: 0 },
    contactTrends: { rising: 0, steady: 0, cooling: 0 },
    contactDecay: { soon: 0, mid: 0, later: 0 },
};

const DASHBOARD_RANGE: RangeKey = '90d';

/**
 * Picks the currency with the most deals from the server-computed {@link DealMetrics}, so the
 * dashboard's aggregate widgets scope to the workspace's dominant currency rather than the
 * currency that happens to dominate a bounded page slice. Falls back to {@code 'USD'}.
 */
function dominantCurrency(metrics: DealMetrics): string {
    let best = 'USD';
    let bestCount = -1;
    for (const entry of metrics.byCurrency) {
        const count = entry.openCount + entry.closedCount;
        if (count > bestCount) {
            bestCount = count;
            best = entry.currency;
        }
    }
    return best;
}

function present<T>(value: T | null): value is T {
    return value != null;
}

export default async function Dashboard() {
    const t = await getTranslations('DashboardPage');

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const emptyFacets: AttachmentFacets = { sources: [], kinds: [], tags: [], orphaned: 0, total: 0, totalSize: 0 };
    const [contacts, deals, pipelines, tasks, activities, notes, users, recentFiles, fileFacets, recentMoves, introSuggestions, dealRisks, layoutResponse, notifications, dealMetrics, companiesPage, contactsPage, activityVolume, leaderboard, taskSummary, upcomingActivityCount, closingSoonCount, warmthSummary, coolingContactTemps, coolingCompanyTemps, closingSoonDeals] =
        await Promise.all([
            getContactsFromCookie(cookie),
            getDealsFromCookie(cookie),
            getPipelinesFromCookie(cookie),
            getTasksFromCookie(cookie),
            getActivitiesFromCookie(cookie),
            getNotesFromCookie(cookie),
            getUsers(init).catch(() => [] as User[]),
            getAttachmentsPage({ size: 6, sort: 'newest' }, init).catch(
                () => ({ items: [], total: 0 }) as Page<Attachment>,
            ),
            getAttachmentFacets(init).catch(() => emptyFacets),
            getRecentMovesFromCookie(cookie),
            getIntroSuggestionsFromCookie(cookie, 4),
            getDealRisksFromCookie(cookie),
            getDashboardLayoutFromCookie(cookie),
            getNotifications({ state: 'unread', page: 1, size: 6 }, init).catch(
                () => ({ items: [], total: 0 }) as Page<Notification>,
            ),
            getDealMetricsFromCookie(cookie).catch(() => ({ byCurrency: [], totalCount: 0 }) as DealMetrics),
            getCompaniesPage({ size: 1 }, init).catch(() => ({ items: [], total: 0 }) as Page<Company>),
            getContactsPage({ size: 1 }, init).catch(() => ({ items: [], total: 0 }) as Page<Contact>),
            getActivityVolumeFromCookie(cookie, DASHBOARD_RANGE).catch(() => [] as ActivityVolumeBucket[]),
            getTeamLeaderboardFromCookie(cookie, DASHBOARD_RANGE).catch(() => [] as TeamLeaderboardEntry[]),
            getTaskSummaryFromCookie(cookie).catch(() => EMPTY_TASK_SUMMARY),
            getUpcomingActivityCountFromCookie(cookie, 7).catch(() => ({ count: 0 }) as Count),
            getDealClosingSoonCountFromCookie(cookie, 7).catch(() => ({ count: 0 }) as Count),
            getWarmthSummaryFromCookie(cookie).catch(() => EMPTY_WARMTH_SUMMARY),
            getCoolingContactTemperaturesFromCookie(cookie, 6).catch(
                () => [] as RelationshipTemperature[],
            ),
            getCoolingCompanyTemperaturesFromCookie(cookie, 6).catch(
                () => [] as RelationshipTemperature[],
            ),
            getDealClosingSoonFromCookie(cookie, 7, 6).catch(() => [] as Deal[]),
        ]);

    const topRisks = dealRisks.slice(0, 6);
    const [riskDeals, coolingContactRecords, coolingCompanyRecords] = await Promise.all([
        Promise.all(topRisks.map((risk) => getDealById(risk.dealId, init).catch(() => null))).then(
            (items) => items.filter(present),
        ),
        Promise.all(coolingContactTemps.map((temp) => getContactById(temp.id, init).catch(() => null))).then(
            (items) => items.filter(present),
        ),
        Promise.all(coolingCompanyTemps.map((temp) => getCompanyById(temp.id, init).catch(() => null))).then(
            (items) => items.filter(present),
        ),
    ]);

    const relatedCompanyIds = new Set(
        [...riskDeals, ...closingSoonDeals]
            .map((deal) => deal.company)
            .filter((companyId): companyId is number => companyId != null),
    );
    const loadedCoolingCompanyIds = new Set(coolingCompanyRecords.map((company) => company.id));
    const relatedCompanies = await Promise.all(
        [...relatedCompanyIds]
            .filter((companyId) => !loadedCoolingCompanyIds.has(companyId))
            .map((companyId) => getCompanyById(companyId, init).catch(() => null)),
    ).then((items) => items.filter(present));

    const stages = (
        await Promise.all(pipelines.map((pipeline) => getStagesByPipelineId(pipeline.id, init).catch(() => [] as Stage[])))
    ).flat();

    const companyById = new Map(
        [...coolingCompanyRecords, ...relatedCompanies].map((company) => [company.id, company]),
    );
    const dealById = new Map(riskDeals.map((deal) => [deal.id, deal]));
    const atRiskDeals: AtRiskItem[] = topRisks
        .map((risk) => ({ risk, deal: dealById.get(risk.dealId) }))
        .filter((entry): entry is { risk: (typeof dealRisks)[number]; deal: Deal } => entry.deal != null)
        .map(({ risk, deal }) => ({
            risk,
            deal,
            company: deal.company != null ? companyById.get(deal.company) : undefined,
        }));

    const coolingContactById = new Map(coolingContactRecords.map((contact) => [contact.id, contact]));
    const coolingContacts: CoolingItem[] = coolingContactTemps.flatMap((temp) => {
        const contact = coolingContactById.get(temp.id);
        return contact ? [{ contact, temp }] : [];
    });

    const overdueTasks = taskSummary.overdue;
    const dueSoon = taskSummary.dueSoon;
    const closingSoon = closingSoonCount.count;
    const upcomingActivities = upcomingActivityCount.count;

    const currency = dominantCurrency(dealMetrics);
    const [dealKpis, pipelineValues, revenueSeries, stageDistribution] = await Promise.all([
        getDealKpisFromCookie(cookie, currency, DASHBOARD_RANGE).catch(() => EMPTY_DEAL_KPIS),
        getDealPipelineValueFromCookie(cookie, currency, DASHBOARD_RANGE).catch(() => [] as DealPipelineValue[]),
        getDealRevenueTimeseries(currency, undefined, init).catch(() => ({ closed: [], projected: [] }) as DealRevenueSeries),
        getDealStageDistribution(currency, init).catch(() => [] as DealStageDistribution[]),
    ]);

    const companyWarmthItems: CompanyWarmthItem[] = coolingCompanyTemps.flatMap((temp) => {
        const company = companyById.get(temp.id);
        return company ? [{ company, temp }] : [];
    });

    const closingSoonItems: ClosingSoonItem[] = closingSoonDeals
        .map((deal) => ({ deal, company: deal.company != null ? companyById.get(deal.company) : undefined }));

    const chartCard = (child: ReactNode) => (
        <div className="h-full rounded-2xl border border-border bg-card p-6">{child}</div>
    );

    const widgetNodes: Record<DashboardWidgetType, ReactNode> = {
        overview: (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <OverviewCard index={0} label={t('companies')} value={companiesPage.total} icon={BuildingOffice2Icon} href="/records/companies" description={t('companiesDescription')} />
                <OverviewCard index={1} label={t('contacts')} value={contactsPage.total} icon={UsersIcon} href="/records/contacts" />
                <OverviewCard index={2} label={t('deals')} value={dealMetrics.totalCount} icon={BriefcaseIcon} href="/records/deals" description={t('dealsDescription')} />
                <OverviewCard index={3} label={t('pipelines')} value={pipelines.length} icon={FunnelIcon} href="/records/pipelines" />
            </div>
        ),
        pipeline: <PipelineChart series={revenueSeries} currency={currency} range={DASHBOARD_RANGE} />,
        tasks: <TaskSummary tasks={tasks} />,
        atRiskDeals: <AtRiskDeals items={atRiskDeals} />,
        coolingRelationships: <CoolingRelationships items={coolingContacts} currentUserId={user.id} />,
        recentMoves: <RecentMoves moves={recentMoves} />,
        introOpportunities: <IntroOpportunities items={introSuggestions} />,
        recentFiles: <RecentFiles files={recentFiles.items} total={fileFacets.total} totalSize={fileFacets.totalSize} />,
        recentActivity: (
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <Timeline tasks={tasks} activities={activities} notes={notes} users={users} persons={contacts} deals={deals} currentUserId={user.id} limit={8} />
            </div>
        ),
        companyWarmth: <CompanyWarmth items={companyWarmthItems} />,
        warmthDistribution: <WarmthDistribution summary={warmthSummary} />,
        closingSoon: <ClosingSoonDeals items={closingSoonItems} />,
        recentNotes: (
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <NoteList notes={notes} />
            </div>
        ),
        notifications: <NotificationsCard items={notifications.items} />,
        quickActions: (
            <div className="flex h-full items-center justify-center rounded-2xl border border-border bg-card p-6">
                <QuickCreate />
            </div>
        ),
        analyticsKpis: <AnalyticsKpisWidget kpis={dealKpis} currency={currency} />,
        revenueTrend: chartCard(<RevenueTrend series={revenueSeries} currency={currency} range={DASHBOARD_RANGE} />),
        winRate: chartCard(<WinRateDonut kpis={dealKpis} currency={currency} />),
        pipelineValue: chartCard(
            <PipelineValue values={pipelineValues} pipelines={pipelines} currency={currency} />,
        ),
        stageFunnel: chartCard(
            <StageFunnel distribution={stageDistribution} pipelines={pipelines} stages={stages} currency={currency} />,
        ),
        activityVolume: chartCard(<ActivityVolume buckets={activityVolume} range={DASHBOARD_RANGE} />),
        teamLeaderboard: chartCard(
            <TeamLeaderboard users={users} standings={leaderboard} />,
        ),
    };

    const initialWidgets = normalizeLayout(layoutResponse.response?.layout);

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-8">
                <Rise>
                    <Greeting
                        user={user}
                        overdueTasks={overdueTasks}
                        dueSoon={dueSoon}
                        closingSoon={closingSoon}
                        upcomingActivities={upcomingActivities}
                    />
                </Rise>
                <Rise delay={0.18}>
                    <DashboardGrid
                        initialWidgets={initialWidgets}
                        nodes={widgetNodes}
                        layoutErrored={layoutResponse.errored}
                    />
                </Rise>
            </div>
        </div>
    );
}
