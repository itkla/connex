import type { DealMetrics } from '@/app/lib/types';

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
