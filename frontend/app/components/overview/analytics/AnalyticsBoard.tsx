'use client';

import { useMemo, useState } from 'react';
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
    type Company,
    type Deal,
    type DealRisk,
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
import { formatCompactCurrency, pickDominantCurrency } from '@/app/lib/utils';
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
import { computeKpis, isClosed, RANGE_DAYS, type RangeKey } from '@/app/components/overview/analytics/metrics';
import { atRiskDealIds, warmSummary } from '@/app/components/overview/analytics/relationshipMetrics';

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
    deals,
    companies,
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
    deals: Deal[];
    companies: Company[];
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
    const [now] = useState(() => Date.now());

    const stageById = useMemo(() => new Map(stages.map((s) => [s.id, s])), [stages]);
    const companyById = useMemo(() => new Map(companies.map((c) => [c.id, c])), [companies]);

    const currencyCounts = useMemo(() => {
        const counts = new Map<string, number>();
        for (const d of deals) {
            const c = d.currency || 'USD';
            counts.set(c, (counts.get(c) ?? 0) + 1);
        }
        return counts;
    }, [deals]);
    const dominantCurrency = useMemo(() => pickDominantCurrency(deals), [deals]);
    const [selectedCurrency, setSelectedCurrency] = useState<string | null>(null);
    const currency =
        selectedCurrency && currencyCounts.has(selectedCurrency) ? selectedCurrency : dominantCurrency;
    const dealsInCurrency = useMemo(
        () => deals.filter((d) => (d.currency || 'USD') === currency),
        [deals, currency],
    );

    const kpis = useMemo(
        () => computeKpis(dealsInCurrency, now, RANGE_DAYS[range]),
        [dealsInCurrency, now, range],
    );

    const openPipeline = useMemo(
        () =>
            dealsInCurrency
                .filter((d) => !isClosed(d))
                .reduce((sum, d) => sum + (d.value ?? 0), 0),
        [dealsInCurrency],
    );

    const warm = useMemo(() => warmSummary(contactTemps), [contactTemps]);

    const dealRisksInCurrency = useMemo(() => {
        const inCurrency = new Set(dealsInCurrency.map((d) => d.id));
        return dealRisks.filter((r) => inCurrency.has(r.dealId));
    }, [dealRisks, dealsInCurrency]);

    const atRisk = useMemo(() => {
        const valueById = new Map(dealsInCurrency.map((d) => [d.id, d.value ?? 0]));
        const ids = atRiskDealIds(dealRisksInCurrency);
        let value = 0;
        for (const id of ids) value += valueById.get(id) ?? 0;
        return { value, count: ids.size };
    }, [dealRisksInCurrency, dealsInCurrency]);

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

    const hasRelationshipData =
        contactTemps.length > 0 ||
        companyTemps.length > 0 ||
        dealRisks.length > 0 ||
        introSuggestions.length > 0 ||
        introLineage.length > 0 ||
        recentMoves.length > 0 ||
        tasks.length > 0;

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
                {deals.length > 0 && (
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
                    ) : deals.length > 0 ? (
                        <span className="rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground ring-1 ring-border">
                            {currency}
                        </span>
                    ) : null}
                    <RangeControl value={range} onChange={setRange} options={rangeOptions} label={t('rangeLabel')} />
                    </div>
                )}
            </header>

            {deals.length === 0 ? (
                <FirstRun />
            ) : (
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
                    <RevenueTrend deals={dealsInCurrency} currency={currency} range={range} />
                </Panel>
            </Reveal>

            <Reveal index={2} reduce={reduce}>
                <Panel
                    title={t('pipelineValueTitle')}
                    info={t('pipelineValueInfo')}
                    infoLabel={t('infoAria')}
                >
                    <PipelineValue
                        deals={dealsInCurrency}
                        pipelines={pipelines}
                        range={range}
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
                    <StageFunnel deals={dealsInCurrency} pipelines={pipelines} stages={stages} currency={currency} />
                </Panel>
                <Panel
                    title={t('winRateTitle')}
                    subtitle={tWinRate('subtitle')}
                    info={t('winRateInfo')}
                    infoLabel={t('infoAria')}
                    className="lg:col-span-2"
                >
                    <WinRateDonut deals={dealsInCurrency} range={range} currency={currency} />
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
                    <DealsAging deals={dealsInCurrency} stageById={stageById} />
                </Panel>
                <Panel
                    title={t('topDealsTitle')}
                    info={t('topDealsInfo')}
                    infoLabel={t('infoAria')}
                    className="lg:col-span-2"
                >
                    <TopDeals deals={dealsInCurrency} companyById={companyById} />
                </Panel>
            </Reveal>

            {hasRelationshipData && (
                <>
                    <Reveal index={6} reduce={reduce}>
                        <div className="pt-4">
                            <h2 className="text-xl font-bold tracking-tight text-foreground md:text-2xl">
                                {t('relationshipsTitle')}
                            </h2>
                            <p className="mt-1 text-sm text-muted-foreground">{t('relationshipsSubtitle')}</p>
                        </div>
                    </Reveal>

                    <Reveal index={7} reduce={reduce}>
                        <RelationshipKpis data={relationshipKpis} currency={currency} />
                    </Reveal>

                    <Reveal index={8} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
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

                    <Reveal index={9} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
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

                    <Reveal index={10} reduce={reduce} className="grid grid-cols-1 gap-6 lg:grid-cols-5">
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