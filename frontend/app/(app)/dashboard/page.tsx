import { headers } from 'next/headers';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    FunnelIcon,
    InboxStackIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';

import {
    getActiveWorkspaceMembersResultFromCookie,
    getActivitiesPageResultFromCookie,
    getActivityVolumeFromCookie,
    getAllStagesResultFromCookie,
    getCapabilitiesResultFromCookie,
    getCaptureOverviewResultFromCookie,
    getAttachmentFacets,
    getAttachmentsPage,
    getCompanyById,
    getCompaniesPageResultFromCookie,
    getContactsPageResultFromCookie,
    getCurrentUserFromCookie,
    getDashboardLayoutFromCookie,
    getDealClosingSoonFromCookie,
    getDealClosingSoonCountFromCookie,
    getDealKpisFromCookie,
    getDealMetricsResultFromCookie,
    getDealPipelineValueFromCookie,
    getDealRevenueTimeseries,
    getDealStageDistribution,
    getDealsPageResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getIntroSuggestionsResultFromCookie,
    getNotesPageResultFromCookie,
    getNotifications,
    getPipelinesResultFromCookie,
    getProviderConnectionsResultFromCookie,
    getRecentMovesResultFromCookie,
    getTaskSummaryFromCookie,
    getTasksPageResultFromCookie,
    getUpcomingTasksFromCookie,
    getTeamLeaderboardFromCookie,
    getUpcomingActivityCountFromCookie,
    getUsers,
    getRelationshipDashboardResultFromCookie,
    toResult,
} from '@/app/lib/api';
import type {
    AttachmentFacets,
    DashboardWidgetType,
    DealKpis,
    DealMetrics,
    DealRevenueSeries,
    NotificationPage,
    RelationshipDashboard,
    Task,
    TaskSummary as TaskSummaryCounts,
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
import { PageShell } from '@/app/components/PageShell';
import SectionUnavailable from '@/app/components/SectionUnavailable';
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
import type { RollingRangeKey } from '@/app/components/overview/analytics/metrics';
import ActivationPanel from '@/app/components/dashboard/activation/ActivationPanel';
import {
    activationGaps,
    buildActivationSteps,
    selectFirstInsight,
    type ActivationCounts,
} from '@/app/lib/activation';

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

const EMPTY_REVENUE_SERIES: DealRevenueSeries = { closed: [], projected: [] };

const EMPTY_TASK_SUMMARY: TaskSummaryCounts = {
    todo: 0,
    inProgress: 0,
    done: 0,
    overdue: 0,
    dueSoon: 0,
};

const EMPTY_ATTACHMENT_FACETS: AttachmentFacets = {
    sources: [],
    kinds: [],
    tags: [],
    orphaned: 0,
    total: 0,
    totalSize: 0,
};

const EMPTY_WARMTH_SUMMARY: WarmthSummary = {
    contacts: { hot: 0, warm: 0, cool: 0, cold: 0 },
    companies: { hot: 0, warm: 0, cool: 0, cold: 0 },
    contactTrends: { rising: 0, steady: 0, cooling: 0 },
    contactDecay: { soon: 0, mid: 0, later: 0 },
};

const EMPTY_RELATIONSHIP_DASHBOARD: RelationshipDashboard = {
    warmthSummary: EMPTY_WARMTH_SUMMARY,
    hasRelationshipEvidence: false,
    coolingContacts: [],
    coolingCompanies: [],
    dealRisks: [],
    dealRisksTruncated: false,
};

const DASHBOARD_RANGE: RollingRangeKey = '90d';

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

/**
 * Loads the workspace-membership and connected-account inputs the setup checklist needs. It runs
 * only while a required setup step is still outstanding, so an established workspace pays nothing
 * for it. Any failed input returns an explicit unavailable result rather than fabricating a gap.
 */
async function loadActivationExtras(cookie: string | null): Promise<{
    ok: true;
    data: {
        members: number;
        connectedAccounts: number;
        connectedCaptureReady: number;
        connectedCaptureAvailable: boolean;
        connectedAccountsAvailable: boolean;
        canImportContacts: boolean;
        canImportCompanies: boolean;
        canCreateActivities: boolean;
        canManagePipelines: boolean;
        canManageMembers: boolean;
        canCreateTasks: boolean;
    };
} | {
    ok: false;
}> {
    const [membersResult, capabilitiesResult, effectivePermissionsResult] = await Promise.all([
        getActiveWorkspaceMembersResultFromCookie(cookie),
        getCapabilitiesResultFromCookie(cookie),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    if (!membersResult.ok || !capabilitiesResult.ok || !effectivePermissionsResult.ok) {
        return { ok: false };
    }
    const capabilities = capabilitiesResult.data;
    const effectivePermissions = effectivePermissionsResult.data;
    const connectedAccountsAvailable =
        capabilities.connectedAccounts.google || capabilities.connectedAccounts.microsoft;
    const connectedCaptureAvailable =
        capabilities.connectedCapture.google || capabilities.connectedCapture.microsoft;
    const connectionsResult = connectedAccountsAvailable
        ? await getProviderConnectionsResultFromCookie(cookie)
        : { ok: true as const, data: [] };
    const captureResult = connectedCaptureAvailable
        ? await getCaptureOverviewResultFromCookie(cookie)
        : { ok: true as const, data: { providers: [] } };
    if (!connectionsResult.ok || !captureResult.ok) {
        return { ok: false };
    }
    return {
        ok: true,
        data: {
            members: membersResult.data.length,
            connectedAccounts: connectionsResult.data.filter(
                (connection) => connection.status === 'connected',
            ).length,
            connectedCaptureReady: captureResult.data.providers.filter(
                (provider) => provider.activationReady,
            ).length,
            connectedCaptureAvailable,
            connectedAccountsAvailable: connectedAccountsAvailable || connectedCaptureAvailable,
            canImportContacts: effectivePermissions.includes('PERSON_CREATE'),
            canImportCompanies: effectivePermissions.includes('COMPANY_CREATE'),
            canCreateActivities: effectivePermissions.includes('ACTIVITY_CREATE'),
            canManagePipelines: effectivePermissions.includes('PIPELINE_MANAGE'),
            canManageMembers: effectivePermissions.includes('MEMBER_MANAGE'),
            canCreateTasks: effectivePermissions.includes('TASK_CREATE'),
        },
    };
}

export default async function Dashboard() {
    const t = await getTranslations('DashboardPage');
    const tUnavailable = await getTranslations('SectionUnavailable');

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [contactsResult, dealsResult, pipelinesResult, stagesResult, tasksResult, upcomingTasks, activitiesResult, notesResult, users, recentFilesResult, fileFacetsResult, recentMovesResult, introSuggestionsResult, relationshipDashboardResult, layoutResponse, notifications, dealMetricsResult, companiesPageResult, contactsPageResult, activityVolumeResult, leaderboardResult, taskSummaryResult, upcomingActivityCountResult, closingSoonCountResult, closingSoonDealsResult, captureOverviewResult] =
        await Promise.all([
            getContactsPageResultFromCookie(cookie, { page: 1, size: 100 }),
            getDealsPageResultFromCookie(cookie, { page: 1, size: 100 }),
            getPipelinesResultFromCookie(cookie),
            getAllStagesResultFromCookie(cookie),
            getTasksPageResultFromCookie(cookie, { page: 1, size: 100 }),
            getUpcomingTasksFromCookie(cookie, 4).catch(() => [] as Task[]),
            getActivitiesPageResultFromCookie(cookie, { page: 1, size: 100 }),
            getNotesPageResultFromCookie(cookie, { page: 1, size: 100 }),
            getUsers(init).catch(() => [] as User[]),
            toResult(getAttachmentsPage({ size: 6, sort: 'newest' }, init)),
            toResult(getAttachmentFacets(init)),
            getRecentMovesResultFromCookie(cookie),
            getIntroSuggestionsResultFromCookie(cookie, 4),
            getRelationshipDashboardResultFromCookie(cookie),
            getDashboardLayoutFromCookie(cookie),
            getNotifications({ status: 'unread', page: 1, size: 6 }, init)
                .catch(() => ({ items: [], total: 0, stateVersion: 0, asOf: '1970-01-01T00:00:00Z' }) as NotificationPage),
            getDealMetricsResultFromCookie(cookie),
            getCompaniesPageResultFromCookie(cookie, { size: 1 }),
            getContactsPageResultFromCookie(cookie, { size: 1 }),
            toResult(getActivityVolumeFromCookie(cookie, DASHBOARD_RANGE)),
            toResult(getTeamLeaderboardFromCookie(cookie, DASHBOARD_RANGE)),
            toResult(getTaskSummaryFromCookie(cookie)),
            toResult(getUpcomingActivityCountFromCookie(cookie, 7)),
            toResult(getDealClosingSoonCountFromCookie(cookie, 7)),
            toResult(getDealClosingSoonFromCookie(cookie, 7, 6)),
            getCaptureOverviewResultFromCookie(cookie),
        ]);

    const contacts = contactsResult.ok ? contactsResult.data.items : [];
    const deals = dealsResult.ok ? dealsResult.data.items : [];
    const tasks = tasksResult.ok ? tasksResult.data.items : [];
    const activities = activitiesResult.ok ? activitiesResult.data.items : [];
    const notes = notesResult.ok ? notesResult.data.items : [];
    const recentMoves = recentMovesResult.ok ? recentMovesResult.data : [];
    const recentFiles = recentFilesResult.ok ? recentFilesResult.data : { items: [], total: 0 };
    const fileFacets = fileFacetsResult.ok ? fileFacetsResult.data : EMPTY_ATTACHMENT_FACETS;
    const activityVolume = activityVolumeResult.ok ? activityVolumeResult.data : [];
    const leaderboard = leaderboardResult.ok ? leaderboardResult.data : [];
    const taskSummary = taskSummaryResult.ok ? taskSummaryResult.data : EMPTY_TASK_SUMMARY;
    const upcomingActivityCount = upcomingActivityCountResult.ok ? upcomingActivityCountResult.data : { count: 0 };
    const closingSoonCount = closingSoonCountResult.ok ? closingSoonCountResult.data : { count: 0 };
    const closingSoonDeals = closingSoonDealsResult.ok ? closingSoonDealsResult.data : [];
    const timelineAvailable = contactsResult.ok
        && dealsResult.ok
        && tasksResult.ok
        && activitiesResult.ok
        && notesResult.ok;
    const introSuggestions = introSuggestionsResult.ok ? introSuggestionsResult.data : [];
    const relationshipDashboard = relationshipDashboardResult.ok
        ? relationshipDashboardResult.data
        : EMPTY_RELATIONSHIP_DASHBOARD;
    const dealMetrics = dealMetricsResult.ok
        ? dealMetricsResult.data
        : { byCurrency: [], totalCount: 0 };
    const pipelines = pipelinesResult.ok ? pipelinesResult.data : [];
    const stages = stagesResult.ok ? stagesResult.data : [];
    const companiesPage = companiesPageResult.ok
        ? companiesPageResult.data
        : { items: [], total: 0 };
    const contactsPage = contactsPageResult.ok
        ? contactsPageResult.data
        : { items: [], total: 0 };
    const captureAttention: Array<{
        provider: string;
        reviews: number;
        interventions: number;
    }> = [];
    if (captureOverviewResult.ok) {
        for (const provider of captureOverviewResult.data.providers) {
            const reviews = provider.reviewCount + provider.pendingApprovalCount;
            const interventions = provider.streams.filter(
                (stream) => stream.status === 'intervention_required',
            ).length;
            if (reviews > 0 || interventions > 0) {
                captureAttention.push({
                    provider: provider.provider,
                    reviews,
                    interventions,
                });
            }
        }
    }

    const relatedCompanyIds = new Set(
        closingSoonDeals.flatMap((deal) => deal.company == null ? [] : [deal.company]),
    );
    const insightCompanies = [
        ...relationshipDashboard.coolingCompanies.map((item) => item.company),
        ...relationshipDashboard.dealRisks.flatMap((item) => item.company ? [item.company] : []),
    ];
    const loadedInsightCompanyIds = new Set(insightCompanies.map((company) => company.id));
    const relatedCompanies = await Promise.all(
        [...relatedCompanyIds].flatMap((companyId) =>
            loadedInsightCompanyIds.has(companyId)
                ? []
                : [getCompanyById(companyId, init).catch(() => null)],
        ),
    ).then((items) => items.filter(present));

    const companyById = new Map(
        [...insightCompanies, ...relatedCompanies].map((company) => [company.id, company]),
    );
    const atRiskDeals: AtRiskItem[] = relationshipDashboard.dealRisks.map(({ risk, deal, company }) => ({
        risk,
        deal,
        company: company ?? undefined,
    }));
    const coolingContacts: CoolingItem[] = relationshipDashboard.coolingContacts.map(
        ({ contact, temperature }) => ({ contact, temp: temperature }),
    );
    const warmthSummary = relationshipDashboard.warmthSummary;

    const overdueTasks = taskSummary.overdue;
    const dueSoon = taskSummary.dueSoon;
    const closingSoon = closingSoonCount.count;
    const upcomingActivities = upcomingActivityCount.count;

    const setupCounts = {
        contacts: contactsPage.total,
        companies: companiesPage.total,
        hasInteractions: relationshipDashboard.hasRelationshipEvidence,
        hasRelationshipTargets: contactsPage.total > 0 || dealMetrics.totalCount > 0,
        pipelines: pipelines.length,
        stages: stages.length,
    };
    const activationCoreInputsAvailable =
        companiesPageResult.ok
        && contactsPageResult.ok
        && dealMetricsResult.ok
        && pipelinesResult.ok
        && stagesResult.ok
        && relationshipDashboardResult.ok;
    const activationNeedsEvaluation =
        !activationCoreInputsAvailable
        || setupCounts.contacts === 0
        || !setupCounts.hasInteractions
        || setupCounts.pipelines === 0
        || setupCounts.stages === 0;
    const activationExtrasPromise = activationNeedsEvaluation ? loadActivationExtras(cookie) : null;

    const currency = dominantCurrency(dealMetrics);
    const [dealKpisResult, pipelineValuesResult, revenueSeriesResult, stageDistributionResult] = await Promise.all([
        toResult(getDealKpisFromCookie(cookie, currency, DASHBOARD_RANGE)),
        toResult(getDealPipelineValueFromCookie(cookie, currency, DASHBOARD_RANGE)),
        toResult(getDealRevenueTimeseries(currency, user.timezone, {}, init)),
        toResult(getDealStageDistribution(currency, {}, init)),
    ]);
    const dealKpis = dealKpisResult.ok ? dealKpisResult.data : EMPTY_DEAL_KPIS;
    const pipelineValues = pipelineValuesResult.ok ? pipelineValuesResult.data : [];
    const revenueSeries = revenueSeriesResult.ok ? revenueSeriesResult.data : EMPTY_REVENUE_SERIES;
    const stageDistribution = stageDistributionResult.ok ? stageDistributionResult.data : [];
    const currencyKnown = dealMetricsResult.ok;
    const kpisAvailable = currencyKnown && dealKpisResult.ok;
    const revenueAvailable = currencyKnown && revenueSeriesResult.ok;
    const pipelineValuesAvailable = currencyKnown && pipelineValuesResult.ok && pipelinesResult.ok;
    const stageFunnelAvailable = currencyKnown && stageDistributionResult.ok && pipelinesResult.ok && stagesResult.ok;

    const companyWarmthItems: CompanyWarmthItem[] = relationshipDashboard.coolingCompanies.map(
        ({ company, temperature }) => ({ company, temp: temperature }),
    );

    const closingSoonItems: ClosingSoonItem[] = closingSoonDeals
        .map((deal) => ({ deal, company: deal.company != null ? companyById.get(deal.company) : undefined }));

    const activationExtrasResult = activationExtrasPromise ? await activationExtrasPromise : null;
    const activationExtras = activationExtrasResult?.ok
        ? activationExtrasResult.data
        : {
            members: 0,
            connectedAccounts: 0,
            connectedCaptureReady: 0,
            connectedCaptureAvailable: false,
            connectedAccountsAvailable: false,
            canImportContacts: false,
            canImportCompanies: false,
            canCreateActivities: false,
            canManagePipelines: false,
            canManageMembers: false,
            canCreateTasks: false,
        };
    const activationCounts: ActivationCounts | null = activationNeedsEvaluation
        ? { ...setupCounts, ...activationExtras }
        : null;
    const activationInputsAvailable =
        activationCoreInputsAvailable && activationExtrasResult?.ok === true;
    const resolvedActivationSteps = activationCounts && activationInputsAvailable
        ? buildActivationSteps(activationCounts)
        : null;
    const activationSteps = resolvedActivationSteps && resolvedActivationSteps.length > 0
        ? resolvedActivationSteps
        : null;
    const activationVisible = activationCounts != null && (
        !activationInputsAvailable
        || resolvedActivationSteps?.some((step) => step.required && !step.done) === true
    );
    const activationInsight = activationCounts && relationshipDashboardResult.ok
        ? selectFirstInsight({
            dealRisks: relationshipDashboard.dealRisks,
            coolingContacts: relationshipDashboard.coolingContacts,
            introSuggestions,
        })
        : null;
    const activationSignalsAvailable = activationInputsAvailable
        && (activationInsight != null || introSuggestionsResult.ok);

    const chartCard = (child: ReactNode) => (
        <div className="h-full rounded-2xl border border-border bg-card p-6">{child}</div>
    );

    const widgetNodes: Record<DashboardWidgetType, ReactNode> = {
        overview: (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <OverviewCard index={0} label={t('companies')} value={companiesPage.total} icon={BuildingOffice2Icon} href="/records/companies" unavailable={!companiesPageResult.ok} unavailableLabel={tUnavailable('title')} />
                <OverviewCard index={1} label={t('contacts')} value={contactsPage.total} icon={UsersIcon} href="/records/contacts" unavailable={!contactsPageResult.ok} unavailableLabel={tUnavailable('title')} />
                <OverviewCard index={2} label={t('deals')} value={dealMetrics.totalCount} icon={BriefcaseIcon} href="/records/deals" unavailable={!dealMetricsResult.ok} unavailableLabel={tUnavailable('title')} />
                <OverviewCard index={3} label={t('pipelines')} value={pipelines.length} icon={FunnelIcon} href="/records/pipelines" unavailable={!pipelinesResult.ok} unavailableLabel={tUnavailable('title')} />
            </div>
        ),
        pipeline: revenueAvailable
            ? <PipelineChart series={revenueSeries} currency={currency} range={DASHBOARD_RANGE} />
            : <SectionUnavailable />,
        tasks: taskSummaryResult.ok
            ? <TaskSummary summary={taskSummary} upcoming={upcomingTasks} />
            : <SectionUnavailable />,
        atRiskDeals: relationshipDashboardResult.ok ? (
            <AtRiskDeals items={atRiskDeals} truncated={relationshipDashboard.dealRisksTruncated} />
        ) : (
            <SectionUnavailable />
        ),
        coolingRelationships: relationshipDashboardResult.ok
            ? <CoolingRelationships items={coolingContacts} currentUserId={user.id} />
            : <SectionUnavailable />,
        recentMoves: recentMovesResult.ok ? <RecentMoves moves={recentMoves} /> : <SectionUnavailable />,
        introOpportunities: introSuggestionsResult.ok
            ? <IntroOpportunities items={introSuggestions} />
            : <SectionUnavailable />,
        recentFiles: recentFilesResult.ok && fileFacetsResult.ok
            ? <RecentFiles files={recentFiles.items} total={fileFacets.total} totalSize={fileFacets.totalSize} />
            : <SectionUnavailable />,
        recentActivity: timelineAvailable ? (
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <Timeline tasks={tasks} activities={activities} notes={notes} users={users} persons={contacts} deals={deals} currentUserId={user.id} limit={8} />
            </div>
        ) : (
            <SectionUnavailable />
        ),
        companyWarmth: relationshipDashboardResult.ok
            ? <CompanyWarmth items={companyWarmthItems} />
            : <SectionUnavailable />,
        warmthDistribution: relationshipDashboardResult.ok
            ? <WarmthDistribution summary={warmthSummary} />
            : <SectionUnavailable />,
        closingSoon: closingSoonDealsResult.ok
            ? <ClosingSoonDeals items={closingSoonItems} />
            : <SectionUnavailable />,
        recentNotes: notesResult.ok ? (
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <NoteList notes={notes} />
            </div>
        ) : (
            <SectionUnavailable />
        ),
        notifications: (
            <NotificationsCard
                key={`${notifications.stateVersion}:${notifications.items.map((item) => item.id).join(',')}`}
                items={notifications.items}
                recipientId={user.id}
                initialStateVersion={notifications.stateVersion}
            />
        ),
        quickActions: (
            <div className="flex h-full items-center justify-center rounded-2xl border border-border bg-card p-6">
                <QuickCreate />
            </div>
        ),
        analyticsKpis: kpisAvailable
            ? <AnalyticsKpisWidget kpis={dealKpis} currency={currency} />
            : <SectionUnavailable />,
        revenueTrend: revenueAvailable
            ? chartCard(
                <RevenueTrend series={revenueSeries} currency={currency} range={DASHBOARD_RANGE} timezone={user.timezone} />,
            )
            : <SectionUnavailable />,
        winRate: kpisAvailable
            ? chartCard(<WinRateDonut kpis={dealKpis} currency={currency} />)
            : <SectionUnavailable />,
        pipelineValue: pipelineValuesAvailable
            ? chartCard(
                <PipelineValue values={pipelineValues} pipelines={pipelines} currency={currency} />,
            )
            : <SectionUnavailable />,
        stageFunnel: stageFunnelAvailable
            ? chartCard(
                <StageFunnel distribution={stageDistribution} pipelines={pipelines} stages={stages} currency={currency} />,
            )
            : <SectionUnavailable />,
        activityVolume: activityVolumeResult.ok
            ? chartCard(<ActivityVolume buckets={activityVolume} range={DASHBOARD_RANGE} />)
            : <SectionUnavailable />,
        teamLeaderboard: leaderboardResult.ok
            ? chartCard(<TeamLeaderboard users={users} standings={leaderboard} />)
            : <SectionUnavailable />,
    };

    const initialWidgets = normalizeLayout(layoutResponse.response?.layout);

    return (
        <PageShell tier="wide">
                <Rise>
                    <Greeting
                        user={user}
                        overdueTasks={overdueTasks}
                        dueSoon={dueSoon}
                        closingSoon={closingSoon}
                        upcomingActivities={upcomingActivities}
                        signalsUnavailable={
                            !taskSummaryResult.ok
                            || !closingSoonCountResult.ok
                            || !upcomingActivityCountResult.ok
                        }
                    />
                </Rise>
                {activationCounts && activationVisible ? (
                    <Rise delay={0.09}>
                        <ActivationPanel
                            steps={activationSteps}
                            insight={activationInsight}
                            gaps={activationGaps(
                                activationCounts,
                                activationInsight != null,
                                activationSignalsAvailable,
                            )}
                            canCreateFollowUp={activationCounts.canCreateTasks}
                        />
                    </Rise>
                ) : null}
                {captureAttention.length > 0 ? (
                    <section
                        className="rounded-2xl border border-warning/40 bg-warning/5 p-5"
                        aria-labelledby="capture-attention-title"
                    >
                        <div className="flex items-start gap-3">
                            <InboxStackIcon
                                className="mt-0.5 size-5 shrink-0 text-warning"
                                aria-hidden
                            />
                            <div className="min-w-0 flex-1">
                                <h2
                                    id="capture-attention-title"
                                    className="text-sm font-semibold text-foreground"
                                >
                                    {t('captureAttention.title')}
                                </h2>
                                <p className="mt-1 text-sm text-muted-foreground">
                                    {t('captureAttention.description')}
                                </p>
                                <div className="mt-3 flex flex-wrap gap-2">
                                    {captureAttention.map((provider) => (
                                        <Link
                                            key={provider.provider}
                                            href={`/account/connections?provider=${provider.provider}&panel=${provider.reviews > 0 ? 'reviews' : 'policy'}`}
                                            className="rounded-md border border-border bg-background px-3 py-2 text-xs font-medium text-foreground hover:bg-accent"
                                        >
                                            {t('captureAttention.action', {
                                                provider: provider.provider === 'google'
                                                    ? 'Google'
                                                    : 'Microsoft',
                                                reviews: provider.reviews,
                                                interventions: provider.interventions,
                                            })}
                                        </Link>
                                    ))}
                                </div>
                            </div>
                        </div>
                    </section>
                ) : null}
                <Rise delay={activationVisible ? 0.18 : 0.09}>
                    <DashboardGrid
                        initialWidgets={initialWidgets}
                        nodes={widgetNodes}
                        layoutErrored={layoutResponse.errored}
                    />
                </Rise>
        </PageShell>
    );
}
