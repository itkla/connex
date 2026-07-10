'use client';

import { useEffect, useMemo, useState } from 'react';
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
    type Activity,
    type DealAging,
    type DealKpis,
    type DealMetrics,
    type DealPipelineValue,
    type DealRevenueSeries,
    type DealRisk,
    type DealStageDistribution,
    type DealTop,
    type IntroSuggestion,
    type IntroductionRecord,
    type JobMove,
    type Note,
    type Pipeline,
    type RelationshipTemperature,
    type Stage,
    type Task,
    type User,
} from '@/app/lib/types';
import {
    getDealAging,
    getDealKpis,
    getDealPipelineValue,
    getDealRevenueTimeseries,
    getDealStageDistribution,
    getDealTop,
} from '@/app/lib/api';
import { formatCompactCurrency } from '@/app/lib/utils';
import DealsAging from '@/app/components/records/deals/DealsAging';
import TopDeals from '@/app/components/records/deals/TopDeals';

import Panel from '@/app/components/overview/analytics/Panel';
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
import { type RangeKey } from '@/app/components/overview/analytics/metrics';
import { warmSummary } from '@/app/components/overview/analytics/relationshipMetrics';

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

export default function AnalyticsBoard({
    dealMetrics,
    pipelines,
    stages,
    activities,
    tasks,
    notes,
    users,
    contactTemps,
    companyTemps,
    dealRisks,
    introSuggestions,
    introLineage,
    recentMoves,
}: {
    dealMetrics: DealMetrics;
    pipelines: Pipeline[];
    stages: Stage[];
    activities: Activity[];
    tasks: Task[];
    notes: Note[];
    users: User[];
    contactTemps: RelationshipTemperature[];
    companyTemps: RelationshipTemperature[];
    dealRisks: DealRisk[];
    introSuggestions: IntroSuggestion[];
    introLineage: IntroductionRecord[];
    recentMoves: JobMove[];
}) {
    const t = useTranslations('AnalyticsPage');
    const tRevenue = useTranslations('AnalyticsRevenue');
    const tWinRate = useTranslations('AnalyticsWinRate');
    const tActivity = useTranslations('AnalyticsActivity');
    const tTeam = useTranslations('AnalyticsTeam');
    const locale = useLocale();
    const reduce = useReducedMotion();
    const [range, setRange] = useState<RangeKey>('90d');

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
    const [selectedCurrency, setSelectedCurrency] = useState<string | null>(null);
    const currency =
        selectedCurrency && currencyCounts.has(selectedCurrency) ? selectedCurrency : dominantCurrency;

    const [kpis, setKpis] = useState<DealKpis>(EMPTY_KPIS);
    const [pipelineValues, setPipelineValues] = useState<DealPipelineValue[]>([]);
    const [aging, setAging] = useState<DealAging[]>([]);
    const [topDeals, setTopDeals] = useState<DealTop>(EMPTY_TOP);
    const [revenueSeries, setRevenueSeries] = useState<DealRevenueSeries>({ closed: [], projected: [] });
    const [stageDistribution, setStageDistribution] = useState<DealStageDistribution[]>([]);

    useEffect(() => {
        let cancelled = false;
        getDealKpis(currency, range)
            .then((data) => { if (!cancelled) setKpis(data); })
            .catch(() => { if (!cancelled) setKpis(EMPTY_KPIS); });
        getDealPipelineValue(currency, range)
            .then((data) => { if (!cancelled) setPipelineValues(data); })
            .catch(() => { if (!cancelled) setPipelineValues([]); });
        return () => { cancelled = true; };
    }, [currency, range]);

    useEffect(() => {
        let cancelled = false;
        getDealRevenueTimeseries(currency)
            .then((data) => { if (!cancelled) setRevenueSeries(data); })
            .catch(() => { if (!cancelled) setRevenueSeries({ closed: [], projected: [] }); });
        getDealStageDistribution(currency)
            .then((data) => { if (!cancelled) setStageDistribution(data); })
            .catch(() => { if (!cancelled) setStageDistribution([]); });
        getDealAging(currency)
            .then((data) => { if (!cancelled) setAging(data); })
            .catch(() => { if (!cancelled) setAging([]); });
        getDealTop(currency)
            .then((data) => { if (!cancelled) setTopDeals(data); })
            .catch(() => { if (!cancelled) setTopDeals(EMPTY_TOP); });
        return () => { cancelled = true; };
    }, [currency]);

    const openPipeline = useMemo(
        () => dealMetrics.byCurrency.find((c) => c.currency === currency)?.openValue ?? 0,
        [dealMetrics, currency],
    );

    const warm = useMemo(() => warmSummary(contactTemps), [contactTemps]);

    const dealRisksInCurrency = useMemo(
        () => dealRisks.filter((r) => r.currency === currency),
        [dealRisks, currency],
    );

    const atRisk = useMemo(() => {
        let value = 0;
        let count = 0;
        for (const r of dealRisksInCurrency) {
            if (r.level !== 'none') {
                value += r.value;
                count += 1;
            }
        }
        return { value, count };
    }, [dealRisksInCurrency]);

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

    const hasDeals = dealMetrics.totalCount > 0;
    const hasRelationshipData =
        contactTemps.length > 0 ||
        companyTemps.length > 0 ||
        dealRisks.length > 0 ||
        introSuggestions.length > 0 ||
        introLineage.length > 0 ||
        recentMoves.length > 0 ||
        tasks.length > 0;
    const relBase = hasDeals ? 6 : 0;

    const rangeOptions: { key: RangeKey; label: string }[] = [
        { key: '30d', label: t('range30d') },
        { key: '90d', label: t('range90d') },
        { key: '12m', label: t('range12m') },
    ];

    return (
        <div className="mx-auto w-full max-w-[100rem] space-y-6 px-2 pb-12">
            <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                <div>
                    <h1 className="text-3xl font-extrabold tracking-tight text-foreground md:text-4xl">{t('title')}</h1>
                    <p className="mt-1.5 text-sm text-muted-foreground">{t('subtitle')}</p>
                </div>
                {(hasDeals || hasRelationshipData) && (
                    <div className="flex items-center gap-2">
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
                    <RangeControl value={range} onChange={setRange} options={rangeOptions} label={t('rangeLabel')} />
                    </div>
                )}
            </header>

            {!hasDeals && !hasRelationshipData ? (
                <FirstRun />
            ) : (
                <>
            {hasDeals && (
                <>
            <Reveal index={0} reduce={reduce}>
                <KpiCluster kpis={kpis} currency={currency} />
            </Reveal>

            <Reveal index={1} reduce={reduce}>
                <Panel
                    title={t('revenueTitle')}
                    subtitle={tRevenue('subtitle')}
                    info={t('revenueInfo')}
                    infoLabel={t('infoAria')}
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
                    <RevenueTrend series={revenueSeries} currency={currency} range={range} />
                </Panel>
            </Reveal>

            <Reveal index={2} reduce={reduce}>
                <Panel
                    title={t('pipelineValueTitle')}
                    info={t('pipelineValueInfo')}
                    infoLabel={t('infoAria')}
                >
                    <PipelineValue
                        values={pipelineValues}
                        pipelines={pipelines}
                        currency={currency}
                    />
                </Panel>
            </Reveal>

            <Reveal index={3} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
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

            <Reveal index={4} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <Panel
                    title={t('activityTitle')}
                    subtitle={tActivity('subtitle')}
                    info={t('activityInfo')}
                    infoLabel={t('infoAria')}
                    className="lg:col-span-3"
                >
                    <ActivityVolume activities={activities} range={range} />
                </Panel>
                <Panel
                    title={t('teamTitle')}
                    subtitle={tTeam('subtitle')}
                    info={t('teamInfo')}
                    infoLabel={t('infoAria')}
                    className="lg:col-span-2"
                >
                    <TeamLeaderboard
                        users={users}
                        activities={activities}
                        tasks={tasks}
                        notes={notes}
                        range={range}
                    />
                </Panel>
            </Reveal>

            <Reveal index={5} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <Panel
                    title={t('agingTitle')}
                    info={t('agingInfo')}
                    infoLabel={t('infoAria')}
                    className="lg:col-span-3"
                >
                    <DealsAging aging={aging} stageById={stageById} />
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
                </>
            )}

            {hasRelationshipData && (
                <>
                    <Reveal index={relBase} reduce={reduce}>
                        <div className={hasDeals ? 'pt-4' : undefined}>
                            <h2 className="text-xl font-bold tracking-tight text-foreground md:text-2xl">
                                {t('relationshipsTitle')}
                            </h2>
                            <p className="mt-1 text-sm text-muted-foreground">{t('relationshipsSubtitle')}</p>
                        </div>
                    </Reveal>

                    <Reveal index={relBase + 1} reduce={reduce}>
                        <RelationshipKpis data={relationshipKpis} currency={currency} />
                    </Reveal>

                    <Reveal index={relBase + 2} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                        <Panel
                            title={t('warmthTitle')}
                            subtitle={t('warmthSubtitle')}
                            info={t('warmthInfo')}
                            infoLabel={t('infoAria')}
                            className="lg:col-span-3"
                        >
                            <WarmthDistribution contacts={contactTemps} companies={companyTemps} />
                        </Panel>
                        <Panel
                            title={t('decayTitle')}
                            info={t('decayInfo')}
                            infoLabel={t('infoAria')}
                            className="lg:col-span-2"
                        >
                            <RelationshipDecay contacts={contactTemps} />
                        </Panel>
                    </Reveal>

                    <Reveal index={relBase + 3} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                        <Panel
                            title={t('dealRiskTitle')}
                            info={t('dealRiskInfo')}
                            infoLabel={t('infoAria')}
                            className="lg:col-span-3"
                        >
                            <DealRiskBreakdown
                                risks={dealRisksInCurrency}
                                pipelineAtRisk={atRisk.value}
                                atRiskDeals={atRisk.count}
                                currency={currency}
                            />
                        </Panel>
                        <Panel
                            title={t('taskStatusTitle')}
                            subtitle={t('taskStatusSubtitle')}
                            info={t('taskStatusInfo')}
                            infoLabel={t('infoAria')}
                            className="lg:col-span-2"
                        >
                            <TaskStatusDonut tasks={tasks} />
                        </Panel>
                    </Reveal>

                    <Reveal index={relBase + 4} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
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
                                range={range}
                            />
                        </Panel>
                        <Panel
                            title={t('movesTitle')}
                            info={t('movesInfo')}
                            infoLabel={t('infoAria')}
                            className="lg:col-span-2"
                        >
                            <RecentMovesList moves={recentMoves} />
                        </Panel>
                    </Reveal>
                </>
            )}
                </>
            )}
        </div>
    );
}