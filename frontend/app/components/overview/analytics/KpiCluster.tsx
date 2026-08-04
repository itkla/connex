'use client';

import { useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowDownIcon, ArrowUpIcon } from '@heroicons/react/16/solid';

import { formatCompactCurrency } from '@/app/lib/utils';
import { type DealKpis } from '@/app/lib/types';
import { type Kpi } from '@/app/components/overview/analytics/metrics';
import InfoTip from '@/app/components/overview/analytics/InfoTip';

const pctChange = (current: number, previous: number): number | null =>
    previous === 0 ? (current === 0 ? 0 : null) : (current - previous) / previous;

/**
 * Maps the server-computed {@link DealKpis} DTO onto the four presentational KPI tiles
 * (won revenue, new pipeline, win rate, average cycle). Deltas come from the DTO's
 * {@code *Prev} baselines — a null baseline yields a null delta, rendered as "no baseline".
 */
function toTiles(kpis: DealKpis): Kpi[] {
    const closedCount = kpis.wonCount + kpis.lostCount;
    const winRate = closedCount > 0 ? kpis.wonCount / closedCount : 0;
    let winRatePrev: number | null = null;
    if (kpis.wonCountPrev != null && kpis.lostCountPrev != null) {
        const prevClosed = kpis.wonCountPrev + kpis.lostCountPrev;
        if (prevClosed > 0) winRatePrev = kpis.wonCountPrev / prevClosed;
    }
    return [
        {
            key: 'wonRevenue',
            format: 'currency',
            value: kpis.wonRevenue,
            delta: kpis.wonRevenuePrev != null ? pctChange(kpis.wonRevenue, kpis.wonRevenuePrev) : null,
            deltaKind: 'pct',
            goodWhenUp: true,
            series: kpis.wonSeries,
        },
        {
            key: 'newPipeline',
            format: 'currency',
            value: kpis.newPipeline,
            delta: kpis.newPipelinePrev != null ? pctChange(kpis.newPipeline, kpis.newPipelinePrev) : null,
            deltaKind: 'pct',
            goodWhenUp: true,
            series: kpis.newPipelineSeries,
        },
        {
            key: 'winRate',
            format: 'percent',
            value: winRate,
            delta: winRatePrev != null && closedCount > 0 ? winRate - winRatePrev : null,
            deltaKind: 'pp',
            goodWhenUp: true,
            series: kpis.winRateSeries,
        },
        {
            key: 'avgCycle',
            format: 'days',
            value: kpis.avgCycleDays,
            delta: kpis.avgCycleDaysPrev != null ? pctChange(kpis.avgCycleDays, kpis.avgCycleDaysPrev) : null,
            deltaKind: 'pct',
            goodWhenUp: false,
            series: kpis.avgCycleSeries,
        },
    ];
}

function Sparkline({ series, positive }: { series: number[]; positive: boolean }) {
    const w = 80;
    const h = 28;
    const pad = 3;
    const stroke = positive ? 'var(--color-brand)' : 'var(--muted-foreground)';
    if (series.length < 2 || series.every((v) => v === 0)) {
        return (
            <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
                <line x1={pad} y1={h - pad} x2={w - pad} y2={h - pad} stroke="var(--border)" strokeWidth={1.5} />
            </svg>
        );
    }
    const max = Math.max(...series);
    const min = Math.min(...series);
    const range = max - min || 1;
    const step = (w - 2 * pad) / (series.length - 1);
    const points = series.map((v, i) => {
        const x = pad + i * step;
        const y = h - pad - ((v - min) / range) * (h - 2 * pad);
        return [x, y] as const;
    });
    const line = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
    const area = `${line} L${(w - pad).toFixed(1)} ${h - pad} L${pad.toFixed(1)} ${h - pad} Z`;
    const gradientId = `spark-${positive ? 'up' : 'flat'}`;
    return (
        <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
            <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={stroke} stopOpacity={0.18} />
                    <stop offset="100%" stopColor={stroke} stopOpacity={0} />
                </linearGradient>
            </defs>
            <path d={area} fill={`url(#${gradientId})`} />
            <path d={line} fill="none" stroke={stroke} strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" />
            <circle cx={points[points.length - 1][0]} cy={points[points.length - 1][1]} r={2.2} fill={stroke} />
        </svg>
    );
}

function DeltaChip({ kpi }: { kpi: Kpi }) {
    const t = useTranslations('AnalyticsKpis');
    if (kpi.delta == null) {
        return <span className="text-xs text-muted-foreground">{t('noBaseline')}</span>;
    }
    const threshold = 0.005;
    const flat = Math.abs(kpi.delta) < threshold;
    const up = kpi.delta > 0;
    const good = flat ? null : up === kpi.goodWhenUp;
    const tone = good == null ? 'text-muted-foreground' : good ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-600 dark:text-red-400';
    const magnitude = Math.round(Math.abs(kpi.delta) * 100);
    const suffix = kpi.deltaKind === 'pp' ? 'pp' : '%';
    const Icon = up ? ArrowUpIcon : ArrowDownIcon;
    return (
        <span className={`inline-flex items-center gap-1 text-xs font-medium tabular-nums ${tone}`}>
            {!flat && <Icon className="size-3" />}
            {magnitude}
            {suffix}
            <span className="font-normal text-muted-foreground">{t('vsPrevious')}</span>
        </span>
    );
}

type SnapshotKpis = {
    activityCount: number;
    warmth?: {
        share: number;
        trackedContacts: number;
    };
};

/**
 * Renders the mixed Analytics overview from the server-computed {@link DealKpis} DTO and snapshot
 * signals: business outcomes, relationship health, and execution activity in one visual strip.
 */
export default function KpiCluster({
    kpis,
    currency,
    snapshot,
}: {
    kpis: DealKpis;
    currency: string;
    snapshot?: SnapshotKpis;
}) {
    const t = useTranslations('AnalyticsKpis');
    const locale = useLocale();
    const tiles = useMemo(() => toTiles(kpis), [kpis]);
    const gridColumns = snapshot
        ? snapshot.warmth
            ? 'lg:grid-cols-3'
            : 'lg:grid-cols-3 2xl:grid-cols-5'
        : 'lg:grid-cols-4';

    const formatValue = (kpi: Kpi) => {
        if (kpi.format === 'currency') return formatCompactCurrency(kpi.value, currency, locale);
        if (kpi.format === 'percent') return `${Math.round(kpi.value * 100)}%`;
        return t('days', { count: Math.round(kpi.value) });
    };

    return (
        <div className={`grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-border ring-1 ring-border sm:grid-cols-2 ${gridColumns}`}>
            {tiles.map((kpi) => (
                <div key={kpi.key} className="flex flex-col gap-3 bg-card p-6">
                    <div className="flex items-start justify-between gap-3">
                        <div className="flex items-center gap-1.5">
                            <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                {t(kpi.key)}
                            </span>
                            <InfoTip
                                title={t(kpi.key)}
                                label={t('infoAria')}
                                body={
                                    <div className="flex flex-col gap-2">
                                        <p>{t(`${kpi.key}Tooltip`)}</p>
                                        <p>{t('comparisonNote')}</p>
                                    </div>
                                }
                            />
                        </div>
                        <Sparkline series={kpi.series} positive={kpi.goodWhenUp} />
                    </div>
                    <span className="text-3xl leading-none text-foreground tabular-nums">{formatValue(kpi)}</span>
                    <DeltaChip kpi={kpi} />
                </div>
            ))}
            {snapshot && (
                <>
                    {snapshot.warmth && (
                        <div className="flex flex-col gap-3 bg-card p-6">
                            <div className="flex items-center gap-1.5">
                                <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                    {t('warmShare')}
                                </span>
                                <InfoTip title={t('warmShare')} label={t('infoAria')} body={t('warmShareTooltip')} />
                            </div>
                            <span className="text-3xl leading-none text-foreground tabular-nums">
                                {Math.round(snapshot.warmth.share * 100)}%
                            </span>
                            <span className="text-xs text-muted-foreground">
                                {t('warmShareCaption', { count: snapshot.warmth.trackedContacts })}
                            </span>
                        </div>
                    )}
                    <div className="flex flex-col gap-3 bg-card p-6">
                        <div className="flex items-center gap-1.5">
                            <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                {t('activity')}
                            </span>
                            <InfoTip title={t('activity')} label={t('infoAria')} body={t('activityTooltip')} />
                        </div>
                        <span className="text-3xl leading-none text-foreground tabular-nums">
                            {snapshot.activityCount.toLocaleString(locale)}
                        </span>
                        <span className="text-xs text-muted-foreground">{t('activityCaption')}</span>
                    </div>
                </>
            )}
        </div>
    );
}
