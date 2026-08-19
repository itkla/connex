'use client';

import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { motion, useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronDownIcon } from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

import {
    type ActivityVolumeBucket,
    type DealAging,
    type DealKpis,
    type DealMetrics,
    type DealPipelineValue,
    type DealRevenuePeriodSeries,
    type DealRiskAnalytics,
    type DealStageDistribution,
    type DealTop,
    type IntroSuggestion,
    type IntroductionRecord,
    type JobMove,
    type Pipeline,
    type Stage,
    type MemberScopeParams,
    type TaskSummary,
    type TeamLeaderboardEntry,
    type User,
    type WarmthSummary,
    type WorkspaceMember,
} from '@/app/lib/types';
import {
    getActiveWorkspaceMembers,
    getActivityVolume,
    getDealAging,
    getDealKpis,
    getDealPipelineValue,
    getDealRevenueSeries,
    getDealRiskAnalytics,
    getDealStageDistribution,
    getDealTop,
    getTaskSummary,
    getTeamLeaderboard,
} from '@/app/lib/api';
import { MemberScopeFilter, interpretMemberScope } from '@/app/components/filters';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { resolveCan } from '@/app/lib/actions/permissions';
import { formatCompactCurrency } from '@/app/lib/utils';
import DealsAging from '@/app/components/records/deals/DealsAging';
import TopDeals from '@/app/components/records/deals/TopDeals';

import PanelBase from '@/app/components/overview/analytics/Panel';
import RangeControl from '@/app/components/overview/analytics/RangeControl';
import KpiCluster from '@/app/components/overview/analytics/KpiCluster';
import RevenueTrend from '@/app/components/overview/analytics/RevenueTrend';
import PipelineValue from '@/app/components/overview/analytics/PipelineValue';
import StageFunnel from '@/app/components/overview/analytics/StageFunnel';
import WinRateDonut from '@/app/components/overview/analytics/WinRateDonut';
import ActivityVolume from '@/app/components/overview/analytics/ActivityVolume';
import TeamLeaderboard from '@/app/components/overview/analytics/TeamLeaderboard';
import RelationshipKpis from '@/app/components/overview/analytics/RelationshipKpis';
import WarmthDistribution from '@/app/components/overview/analytics/WarmthDistribution';
import RelationshipDecay from '@/app/components/overview/analytics/RelationshipDecay';
import DealRiskBreakdown from '@/app/components/overview/analytics/DealRiskBreakdown';
import TaskStatusDonut from '@/app/components/overview/analytics/TaskStatusDonut';
import IntroActivity from '@/app/components/overview/analytics/IntroActivity';
import RecentMovesList from '@/app/components/overview/analytics/RecentMovesList';
import FirstRun from '@/app/components/overview/analytics/FirstRun';
import SectionUnavailable from '@/app/components/SectionUnavailable';
import {
    clampGranularity,
    DEFAULT_GRANULARITY,
    granularitiesForRange,
    isGranularity,
    isRangeKey,
    localIsoDate,
    parseCustomAnalyticsWindow,
    projectionWindow,
    resolveAnalyticsWindow,
    type AnalyticsWindow,
    type Granularity,
    type RangeKey,
} from '@/app/components/overview/analytics/metrics';

const EMPTY_KPIS: DealKpis = {
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

const EMPTY_TOP: DealTop = { topOpen: [], topWon: [] };
const EMPTY_REVENUE: DealRevenuePeriodSeries = { realized: [], projected: [] };
const EMPTY_PIPELINE_VALUES: DealPipelineValue[] = [];
const EMPTY_AGING: DealAging[] = [];
const EMPTY_STAGE_DISTRIBUTION: DealStageDistribution[] = [];
const EMPTY_ACTIVITY_BUCKETS: ActivityVolumeBucket[] = [];
const EMPTY_LEADERBOARD: TeamLeaderboardEntry[] = [];
const EMPTY_RISK: DealRiskAnalytics = { currencies: [], truncated: false };
const EMPTY_TASKS: TaskSummary = { todo: 0, inProgress: 0, done: 0, overdue: 0, dueSoon: 0 };
const ALL_TEAM_KEY = '{}';

type ScopedData<T> = {
    scope: string;
    data: T;
};

function dataForScope<T>(value: ScopedData<T>, scope: string, empty: T): T {
    return value.scope === scope ? value.data : empty;
}

function Reveal({
    children,
    index,
    reduce,
    className,
}: {
    children: React.ReactNode;
    index: number;
    reduce: boolean | null;
    className?: string;
}) {
    return (
        <motion.div
            className={className}
            initial={reduce ? false : { opacity: 0, y: 8 }}
            animate={reduce ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.4, delay: index * 0.06, ease: [0.16, 1, 0.3, 1] }}
        >
            {children}
        </motion.div>
    );
}

function SectionHeading({ title, description }: { title: string; description: string }) {
    return (
        <div>
            <h2 className="text-xl font-bold tracking-tight text-foreground md:text-2xl">{title}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        </div>
    );
}

function Panel(props: Omit<React.ComponentProps<typeof PanelBase>, 'headingLevel'>) {
    return <PanelBase {...props} headingLevel={3} />;
}

export default function AnalyticsBoard({
    dealMetrics,
    timezone,
    pipelines,
    stages,
    users,
    dealRiskAnalytics,
    introSuggestions,
    introLineage,
    recentMoves,
    recentMovesAvailable,
    taskSummary,
    warmth,
}: {
    dealMetrics: DealMetrics;
    timezone: string;
    pipelines: Pipeline[];
    stages: Stage[];
    users: User[];
    dealRiskAnalytics: DealRiskAnalytics;
    introSuggestions: IntroSuggestion[];
    introLineage: IntroductionRecord[];
    recentMoves: JobMove[];
    recentMovesAvailable: boolean;
    taskSummary: TaskSummary;
    warmth: WarmthSummary;
}) {
    const t = useTranslations('AnalyticsPage');
    const tRevenue = useTranslations('AnalyticsRevenue');
    const tWinRate = useTranslations('AnalyticsWinRate');
    const tActivity = useTranslations('AnalyticsActivity');
    const tTeam = useTranslations('AnalyticsTeam');
    const locale = useLocale();
    const reduce = useReducedMotion();
    const searchParams = useSearchParams();
    const [rangeState, setRangeState] = useState<{
        key: RangeKey;
        customWindow: AnalyticsWindow | null;
    }>(() => {
        const requested = searchParams.get('range');
        const customWindow = parseCustomAnalyticsWindow(searchParams.get('from'), searchParams.get('to'));
        const key = isRangeKey(requested) ? requested : '90d';
        return {
            key: key === 'custom' && !customWindow ? '90d' : key,
            customWindow,
        };
    });
    const range = rangeState.key;
    const [granularityChoice, setGranularityChoice] = useState<Granularity | null>(() => {
        const initial = searchParams.get('granularity');
        return isGranularity(initial) ? initial : null;
    });
    const [now] = useState(() => Date.now());
    const analyticsWindow = useMemo(
        () => resolveAnalyticsWindow(range, now, timezone, rangeState.customWindow),
        [range, rangeState.customWindow, now, timezone],
    );
    const granularity = clampGranularity(range, granularityChoice, analyticsWindow);
    const revenueWindow = useMemo(
        () => projectionWindow(analyticsWindow, range, granularity),
        [analyticsWindow, range, granularity],
    );
    const [ownerValues, setOwnerValues] = useState<string[]>(() => {
        const initial = searchParams.get('owner');
        return initial ? initial.split(',').filter(Boolean) : [];
    });

    const { activeWorkspace } = useWorkspace();
    const canScopeByMember = useMemo(() => resolveCan(activeWorkspace)('WORKSPACE_MANAGE'), [activeWorkspace]);

    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    useEffect(() => {
        if (!canScopeByMember) return;
        getActiveWorkspaceMembers().then(setMembers).catch(() => setMembers([]));
    }, [canScopeByMember]);
    const activeMembers = useMemo(() => members.filter((member) => member.status === 'active'), [members]);
    const effectiveOwnerValues = useMemo(
        () => (canScopeByMember ? ownerValues : []),
        [canScopeByMember, ownerValues],
    );
    const ownerScope = useMemo(() => interpretMemberScope(effectiveOwnerValues), [effectiveOwnerValues]);
    const scopeParams = useMemo<MemberScopeParams>(() => ({
        scope: ownerScope.mode === 'all' ? undefined : ownerScope.mode,
        memberIds: ownerScope.mode === 'members' ? ownerScope.memberIds : undefined,
    }), [ownerScope]);
    const memberKey = useMemo(() => JSON.stringify(scopeParams), [scopeParams]);

    const stageById = useMemo(() => new Map(stages.map((s) => [s.id, s])), [stages]);

    const currencyCounts = useMemo(() => {
        const counts = new Map<string, number>();
        for (const c of dealMetrics.byCurrency) counts.set(c.currency, c.openCount + c.closedCount);
        return counts;
    }, [dealMetrics]);
    const dominantCurrency = useMemo(() => {
        let best: string | null = null;
        let bestCount = -1;
        for (const c of dealMetrics.byCurrency) {
            const n = c.openCount + c.closedCount;
            if (n > bestCount) {
                bestCount = n;
                best = c.currency;
            }
        }
        return best ?? 'USD';
    }, [dealMetrics]);
    const [selectedCurrency, setSelectedCurrency] = useState<string | null>(() => searchParams.get('currency'));
    const currency =
        selectedCurrency && currencyCounts.has(selectedCurrency) ? selectedCurrency : dominantCurrency;

    useOwnedUrlParams({
        range: range === '90d' ? undefined : range,
        from: range === 'custom' ? analyticsWindow.from : undefined,
        to: range === 'custom' ? analyticsWindow.to : undefined,
        granularity: granularity === DEFAULT_GRANULARITY[range] ? undefined : granularity,
        currency: selectedCurrency && currencyCounts.has(selectedCurrency) ? selectedCurrency : undefined,
        owner: effectiveOwnerValues.length ? effectiveOwnerValues.join(',') : undefined,
    });

    const windowKey = `${analyticsWindow.from}:${analyticsWindow.to}`;
    const kpiScope = `${currency}:${windowKey}:${granularity}:${memberKey}`;
    const pipelineScope = `${currency}:${windowKey}:${memberKey}`;
    const revenueScope = `${currency}:${revenueWindow.from}:${revenueWindow.to}:${granularity}:${memberKey}`;
    const currencyScope = `${currency}:${memberKey}`;
    const volumeScope = `${windowKey}:${granularity}:${memberKey}`;
    const [kpisResult, setKpisResult] = useState<ScopedData<DealKpis>>({ scope: '', data: EMPTY_KPIS });
    const [pipelineResult, setPipelineResult] = useState<ScopedData<DealPipelineValue[]>>({
        scope: '',
        data: EMPTY_PIPELINE_VALUES,
    });
    const [agingResult, setAgingResult] = useState<ScopedData<DealAging[]>>({ scope: '', data: EMPTY_AGING });
    const [topDealsResult, setTopDealsResult] = useState<ScopedData<DealTop>>({ scope: '', data: EMPTY_TOP });
    const [revenueResult, setRevenueResult] = useState<ScopedData<DealRevenuePeriodSeries>>({
        scope: '',
        data: EMPTY_REVENUE,
    });
    const [stageResult, setStageResult] = useState<ScopedData<DealStageDistribution[]>>({
        scope: '',
        data: EMPTY_STAGE_DISTRIBUTION,
    });
    const [activityResult, setActivityResult] = useState<ScopedData<ActivityVolumeBucket[]>>({
        scope: '',
        data: EMPTY_ACTIVITY_BUCKETS,
    });
    const [leaderboardResult, setLeaderboardResult] = useState<ScopedData<TeamLeaderboardEntry[]>>({
        scope: '',
        data: EMPTY_LEADERBOARD,
    });

    const kpis = dataForScope(kpisResult, kpiScope, EMPTY_KPIS);
    const pipelineValues = dataForScope(pipelineResult, pipelineScope, EMPTY_PIPELINE_VALUES);
    const aging = dataForScope(agingResult, currencyScope, EMPTY_AGING);
    const topDeals = dataForScope(topDealsResult, currencyScope, EMPTY_TOP);
    const revenueSeries = dataForScope(revenueResult, revenueScope, EMPTY_REVENUE);
    const stageDistribution = dataForScope(stageResult, currencyScope, EMPTY_STAGE_DISTRIBUTION);
    const activityBuckets = dataForScope(activityResult, volumeScope, EMPTY_ACTIVITY_BUCKETS);
    const leaderboard = dataForScope(leaderboardResult, windowKey, EMPTY_LEADERBOARD);

    useEffect(() => {
        let cancelled = false;
        getDealKpis(currency, undefined, scopeParams, { ...analyticsWindow, granularity, timezone })
            .then((data) => { if (!cancelled) setKpisResult({ scope: kpiScope, data }); })
            .catch(() => { if (!cancelled) setKpisResult({ scope: kpiScope, data: EMPTY_KPIS }); });
        return () => { cancelled = true; };
    }, [currency, kpiScope, analyticsWindow, granularity, timezone, scopeParams]);

    useEffect(() => {
        let cancelled = false;
        getDealPipelineValue(currency, undefined, scopeParams, { ...analyticsWindow, timezone })
            .then((data) => { if (!cancelled) setPipelineResult({ scope: pipelineScope, data }); })
            .catch(() => {
                if (!cancelled) setPipelineResult({ scope: pipelineScope, data: EMPTY_PIPELINE_VALUES });
            });
        return () => { cancelled = true; };
    }, [currency, pipelineScope, analyticsWindow, timezone, scopeParams]);

    useEffect(() => {
        let cancelled = false;
        getDealRevenueSeries({ ...revenueWindow, granularity, timezone }, currency, scopeParams)
            .then((data) => { if (!cancelled) setRevenueResult({ scope: revenueScope, data }); })
            .catch(() => { if (!cancelled) setRevenueResult({ scope: revenueScope, data: EMPTY_REVENUE }); });
        return () => { cancelled = true; };
    }, [currency, revenueScope, revenueWindow, granularity, timezone, scopeParams]);

    useEffect(() => {
        let cancelled = false;
        getDealStageDistribution(currency, scopeParams)
            .then((data) => { if (!cancelled) setStageResult({ scope: currencyScope, data }); })
            .catch(() => {
                if (!cancelled) setStageResult({ scope: currencyScope, data: EMPTY_STAGE_DISTRIBUTION });
            });
        getDealAging(currency, scopeParams)
            .then((data) => { if (!cancelled) setAgingResult({ scope: currencyScope, data }); })
            .catch(() => { if (!cancelled) setAgingResult({ scope: currencyScope, data: EMPTY_AGING }); });
        getDealTop(currency, scopeParams)
            .then((data) => { if (!cancelled) setTopDealsResult({ scope: currencyScope, data }); })
            .catch(() => { if (!cancelled) setTopDealsResult({ scope: currencyScope, data: EMPTY_TOP }); });
        return () => { cancelled = true; };
    }, [currency, currencyScope, scopeParams]);

    useEffect(() => {
        let cancelled = false;
        getActivityVolume(undefined, scopeParams, { ...analyticsWindow, granularity, timezone })
            .then((data) => { if (!cancelled) setActivityResult({ scope: volumeScope, data }); })
            .catch(() => { if (!cancelled) setActivityResult({ scope: volumeScope, data: EMPTY_ACTIVITY_BUCKETS }); });
        return () => { cancelled = true; };
    }, [volumeScope, analyticsWindow, granularity, timezone, scopeParams]);

    useEffect(() => {
        let cancelled = false;
        getTeamLeaderboard(undefined, { ...analyticsWindow, timezone })
            .then((data) => { if (!cancelled) setLeaderboardResult({ scope: windowKey, data }); })
            .catch(() => { if (!cancelled) setLeaderboardResult({ scope: windowKey, data: EMPTY_LEADERBOARD }); });
        return () => { cancelled = true; };
    }, [windowKey, analyticsWindow, timezone]);

    const [riskResult, setRiskResult] = useState<ScopedData<DealRiskAnalytics>>({ scope: ALL_TEAM_KEY, data: dealRiskAnalytics });
    const [taskResult, setTaskResult] = useState<ScopedData<TaskSummary>>({ scope: ALL_TEAM_KEY, data: taskSummary });
    useEffect(() => {
        if (ownerScope.mode === 'all') return;
        let cancelled = false;
        getDealRiskAnalytics(scopeParams)
            .then((data) => { if (!cancelled) setRiskResult({ scope: memberKey, data }); })
            .catch(() => { if (!cancelled) setRiskResult({ scope: memberKey, data: EMPTY_RISK }); });
        getTaskSummary(scopeParams)
            .then((data) => { if (!cancelled) setTaskResult({ scope: memberKey, data }); })
            .catch(() => { if (!cancelled) setTaskResult({ scope: memberKey, data: EMPTY_TASKS }); });
        return () => { cancelled = true; };
    }, [memberKey, ownerScope.mode, scopeParams]);
    const riskAnalytics = ownerScope.mode === 'all' ? dealRiskAnalytics : dataForScope(riskResult, memberKey, EMPTY_RISK);
    const tasks = ownerScope.mode === 'all' ? taskSummary : dataForScope(taskResult, memberKey, EMPTY_TASKS);

    const revenuePeriods = useMemo(
        () => ({ series: revenueSeries, granularity }),
        [revenueSeries, granularity],
    );

    const openPipeline = useMemo(
        () => dealMetrics.byCurrency.find((c) => c.currency === currency)?.openValue ?? 0,
        [dealMetrics, currency],
    );

    const warm = useMemo(() => {
        const { hot, warm: warmBand, cool, cold } = warmth.contacts;
        const tracked = hot + warmBand + cool + cold;
        const warmCount = hot + warmBand;
        return {
            tracked,
            share: tracked > 0 ? warmCount / tracked : 0,
            cooling: warmth.contactTrends.cooling,
        };
    }, [warmth]);

    const riskSummary = useMemo(
        () => riskAnalytics.currencies.find((entry) => entry.currency === currency),
        [riskAnalytics, currency],
    );
    const atRisk = useMemo(
        () => ({ value: riskSummary?.value ?? 0, count: riskSummary?.count ?? 0 }),
        [riskSummary],
    );

    const relationshipKpis = useMemo(
        () => ({
            tracked: warm.tracked,
            warmShare: warm.share,
            cooling: warm.cooling,
            pipelineAtRisk: atRisk.value,
            atRiskDeals: atRisk.count,
            introOpportunities: introSuggestions.length,
        }),
        [warm, atRisk, introSuggestions],
    );

    const companiesTracked =
        warmth.companies.hot + warmth.companies.warm + warmth.companies.cool + warmth.companies.cold;
    const tasksTracked = tasks.todo + tasks.inProgress + tasks.done;

    const hasDeals = dealMetrics.totalCount > 0;
    const hasRelationshipData =
        warm.tracked > 0 ||
        companiesTracked > 0 ||
        riskAnalytics.currencies.some((entry) => entry.count > 0) ||
        introSuggestions.length > 0 ||
        introLineage.length > 0 ||
        recentMoves.length > 0 ||
        !recentMovesAvailable ||
        tasksTracked > 0;
    const activityTotal = useMemo(
        () => activityBuckets.reduce(
            (total, bucket) => total + bucket.call + bucket.email + bucket.meeting + bucket.note + bucket.other,
            0,
        ),
        [activityBuckets],
    );
    const relationshipRevealBase = hasDeals ? 8 : 3;

    const rangeOptions: { key: RangeKey; label: string }[] = [
        { key: '30d', label: t('range30d') },
        { key: '90d', label: t('range90d') },
        { key: '12m', label: t('range12m') },
    ];
    const granularityLabels: Record<Granularity, string> = {
        day: t('granularityDay'),
        week: t('granularityWeek'),
        month: t('granularityMonth'),
    };
    const granularityOptions = granularitiesForRange(range, analyticsWindow).map((key) => ({
        key,
        label: granularityLabels[key],
    }));

    return (
        <div className="w-full space-y-6 px-2 pb-12 2xl:px-6">
            <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                <div>
                    <h1 className="text-4xl font-extrabold tracking-tight text-foreground">{t('title')}</h1>
                    <p className="mt-1.5 text-sm text-muted-foreground">{t('subtitle')}</p>
                </div>
                {(hasDeals || hasRelationshipData) && (
                    <div className="flex flex-wrap items-center gap-2">
                    {canScopeByMember && (
                        <MemberScopeFilter
                            values={ownerValues}
                            onChange={setOwnerValues}
                            members={activeMembers}
                            label={t('userFilter')}
                            ariaLabel={t('userFilterAria')}
                        />
                    )}
                    {currencyCounts.size > 1 ? (
                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <button
                                    type="button"
                                    aria-label={t('currency')}
                                    className="flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-foreground ring-1 ring-border transition hover:bg-muted/80"
                                >
                                    {currency}
                                    <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                                </button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                {Array.from(currencyCounts.entries())
                                    .sort((a, b) => b[1] - a[1])
                                    .map(([code, count]) => (
                                        <DropdownMenuItem key={code} onSelect={() => setSelectedCurrency(code)}>
                                            <span className={code === currency ? 'font-semibold' : ''}>{code}</span>
                                            <span className="ml-auto text-xs text-muted-foreground">
                                                {t('currencyCount', { count })}
                                            </span>
                                        </DropdownMenuItem>
                                    ))}
                            </DropdownMenuContent>
                        </DropdownMenu>
                    ) : hasDeals ? (
                        <span className="rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground ring-1 ring-border">
                            {currency}
                        </span>
                    ) : null}
                    <RangeControl
                        value={range}
                        onChange={(key) => setRangeState((current) => ({ ...current, key }))}
                        options={rangeOptions}
                        customRange={{
                            key: 'custom',
                            value: analyticsWindow,
                            locale,
                            today: localIsoDate(now, timezone),
                            timezone,
                            scope: scopeParams,
                            labels: {
                                custom: t('rangeCustom'),
                                title: t('rangeCustomTitle'),
                                description: t('rangeCustomDescription', { timezone }),
                                start: t('rangeCustomStart'),
                                end: t('rangeCustomEnd'),
                                days: (count: number) => t('rangeCustomDays', { count }),
                                activities: (count: number) => t('rangeCustomActivities', { count }),
                                grid: t('rangeCustomGrid'),
                                previous: t('rangeCustomPrevious'),
                                next: t('rangeCustomNext'),
                                zoomMonths: t('rangeCustomZoomMonths'),
                                zoomYears: t('rangeCustomZoomYears'),
                                cancel: t('rangeCustomCancel'),
                                apply: t('rangeCustomApply'),
                            },
                            onApply: (customWindow) => setRangeState({ key: 'custom', customWindow }),
                        }}
                        label={t('rangeLabel')}
                    />
                    <RangeControl
                        value={granularity}
                        onChange={setGranularityChoice}
                        options={granularityOptions}
                        label={t('granularityLabel')}
                        layoutId="analytics-granularity-thumb"
                    />
                    </div>
                )}
            </header>

            {!hasDeals && !hasRelationshipData ? (
                <FirstRun />
            ) : (
                <>
                    <Reveal index={0} reduce={reduce}>
                        <SectionHeading title={t('overviewTitle')} description={t('overviewSubtitle')} />
                    </Reveal>

                    <Reveal index={1} reduce={reduce}>
                        {hasDeals ? (
                            <KpiCluster
                                kpis={kpis}
                                currency={currency}
                                snapshot={{
                                    activityCount: activityTotal,
                                    warmth: ownerScope.mode === 'all' && warm.tracked > 0
                                        ? { share: warm.share, trackedContacts: warm.tracked }
                                        : undefined,
                                }}
                            />
                        ) : (
                            <RelationshipKpis data={relationshipKpis} currency={currency} />
                        )}
                    </Reveal>

                    {hasDeals && (
                        <>
                            <Reveal index={2} reduce={reduce}>
                                <SectionHeading title={t('trendsTitle')} description={t('trendsSubtitle')} />
                            </Reveal>

                            <Reveal index={3} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('revenueTitle')}
                                    subtitle={tRevenue('subtitle')}
                                    info={t('revenueInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                    action={
                                        <div className="text-right">
                                            <div className="text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                                {tRevenue('openPipeline')}
                                            </div>
                                            <div className="mt-0.5 text-lg font-semibold tabular-nums text-foreground">
                                                {formatCompactCurrency(openPipeline, currency, locale)}
                                            </div>
                                        </div>
                                    }
                                >
                                    <RevenueTrend periods={revenuePeriods} currency={currency} timezone={timezone} />
                                </Panel>
                                <Panel
                                    title={t('activityTitle')}
                                    subtitle={tActivity('subtitle')}
                                    info={t('activityInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <ActivityVolume buckets={activityBuckets} granularity={granularity} />
                                </Panel>
                            </Reveal>
                        </>
                    )}

                    <Reveal index={hasDeals ? 4 : 2} reduce={reduce}>
                        <SectionHeading title={t('diagnosticsTitle')} description={t('diagnosticsSubtitle')} />
                    </Reveal>

                    {hasDeals && (
                        <>
                            <Reveal index={5} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('pipelineValueTitle')}
                                    info={t('pipelineValueInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <PipelineValue
                                        values={pipelineValues}
                                        pipelines={pipelines}
                                        currency={currency}
                                    />
                                </Panel>
                                <Panel
                                    title={t('topDealsTitle')}
                                    info={t('topDealsInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <TopDeals data={topDeals} />
                                </Panel>
                            </Reveal>

                            <Reveal index={6} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('stageTitle')}
                                    info={t('stageInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <StageFunnel distribution={stageDistribution} pipelines={pipelines} stages={stages} currency={currency} />
                                </Panel>
                                <Panel
                                    title={t('winRateTitle')}
                                    subtitle={tWinRate('subtitle')}
                                    info={t('winRateInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <WinRateDonut kpis={kpis} currency={currency} />
                                </Panel>
                            </Reveal>

                            <Reveal index={7} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('agingTitle')}
                                    info={t('agingInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <DealsAging aging={aging} stageById={stageById} />
                                </Panel>
                                <Panel
                                    title={t('teamTitle')}
                                    subtitle={tTeam('subtitle')}
                                    info={t('teamInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <TeamLeaderboard users={users} standings={leaderboard} />
                                </Panel>
                            </Reveal>
                        </>
                    )}

                    {hasRelationshipData && (
                        <>
                            <Reveal index={relationshipRevealBase} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('warmthTitle')}
                                    subtitle={t('warmthSubtitle')}
                                    info={t('warmthInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <WarmthDistribution summary={warmth} />
                                </Panel>
                                <Panel
                                    title={t('decayTitle')}
                                    info={t('decayInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <RelationshipDecay decay={warmth.contactDecay} />
                                </Panel>
                            </Reveal>

                            <Reveal index={relationshipRevealBase + 1} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('dealRiskTitle')}
                                    info={t('dealRiskInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <DealRiskBreakdown
                                        summary={riskSummary}
                                        currency={currency}
                                        truncated={riskAnalytics.truncated}
                                    />
                                </Panel>
                                <Panel
                                    title={t('taskStatusTitle')}
                                    subtitle={t('taskStatusSubtitle')}
                                    info={t('taskStatusInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-2"
                                >
                                    <TaskStatusDonut
                                        counts={{
                                            todo: tasks.todo,
                                            inProgress: tasks.inProgress,
                                            done: tasks.done,
                                        }}
                                    />
                                </Panel>
                            </Reveal>

                            <Reveal index={relationshipRevealBase + 2} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                                <Panel
                                    title={t('introsTitle')}
                                    subtitle={t('introsSubtitle')}
                                    info={t('introsInfo')}
                                    infoLabel={t('infoAria')}
                                    className="lg:col-span-3"
                                >
                                    <IntroActivity
                                        suggestions={introSuggestions}
                                        lineage={introLineage}
                                        analyticsWindow={analyticsWindow}
                                        granularity={granularity}
                                    />
                                </Panel>
                                {recentMovesAvailable ? (
                                    <Panel
                                        title={t('movesTitle')}
                                        info={t('movesInfo')}
                                        infoLabel={t('infoAria')}
                                        className="lg:col-span-2"
                                    >
                                        <RecentMovesList moves={recentMoves} />
                                    </Panel>
                                ) : (
                                    <div className="lg:col-span-2">
                                        <SectionUnavailable />
                                    </div>
                                )}
                            </Reveal>
                        </>
                    )}
                </>
            )}
        </div>
    );
}
