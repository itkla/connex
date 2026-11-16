import type { Deal, DealKpis, DealMetrics } from '@/app/lib/types';
import type { Kpi } from '@/app/components/overview/analytics/metrics';

/** Counts every open and closed deal per currency, preserving the metrics endpoint's order. */
export function currencyCountsForMetrics(metrics: DealMetrics): Map<string, number> {
    return new Map(metrics.byCurrency.map((entry) => [entry.currency, entry.openCount + entry.closedCount]));
}

/** Uses a valid explicit currency, otherwise the most common currency (USD when empty). */
export function resolveCurrency(counts: ReadonlyMap<string, number>, selected: string | null): string {
    if (selected && counts.has(selected)) return selected;
    let best = 'USD';
    let bestCount = -1;
    for (const [currency, count] of counts) {
        if (count > bestCount) {
            best = currency;
            bestCount = count;
        }
    }
    return best;
}

/** Resolves URL currencies against known buckets, retaining known selections with no filter matches. */
export function resolveDealViewCurrency(
    counts: ReadonlyMap<string, number>,
    selected: string | null,
    workspaceCounts: ReadonlyMap<string, number> = counts,
): string {
    const filteredCounts = new Map([...counts].filter(([code]) => code.trim().length > 0));
    const knownCounts = new Map([...workspaceCounts, ...filteredCounts].filter(([code]) => code.trim().length > 0));
    const requested = selected?.trim().toUpperCase();
    if (requested) {
        const known = [...knownCounts.keys()].find((code) => code.toUpperCase() === requested);
        if (known) return known;
    }
    return resolveCurrency(filteredCounts.size > 0 ? filteredCounts : knownCounts, null);
}

/** Rebuilds currency counts in the metrics endpoint's currency order from an authorized deal list. */
export function currencyCountsForDeals(deals: readonly Pick<Deal, 'currency'>[]): Map<string, number> {
    const counts = new Map<string, number>();
    for (const deal of deals) counts.set(deal.currency, (counts.get(deal.currency) ?? 0) + 1);
    return new Map([...counts].sort(([left], [right]) => left.localeCompare(right)));
}

const pctChange = (current: number, previous: number): number | null =>
    previous === 0 ? (current === 0 ? 0 : null) : (current - previous) / previous;

/**
 * Maps the server-computed {@link DealKpis} DTO onto the four presentational KPI tiles
 * (won revenue, new pipeline, win rate, average cycle). Deltas come from the DTO's
 * {@code *Prev} baselines — a null baseline yields a null delta, rendered as "no baseline".
 */
export function toTiles(kpis: DealKpis): Kpi[] {
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
