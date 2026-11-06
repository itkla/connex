import type { Deal, DealKpis } from "@/app/lib/types";
import { parseMysqlDateTime } from "@/app/lib/utils";
import { analyticsWindowDays, localIsoDate, periodStartOf, type AnalyticsWindow } from "@/app/components/overview/analytics/metrics";

const DAY = 86_400_000;

type Period = {
    createdValue: number;
    createdCount: number;
    wonValue: number;
    lostValue: number;
    wonCount: number;
    lostCount: number;
    cycleSum: number;
    cycleCount: number;
};

function emptyPeriod(): Period {
    return { createdValue: 0, createdCount: 0, wonValue: 0, lostValue: 0, wonCount: 0, lostCount: 0, cycleSum: 0, cycleCount: 0 };
}

function recordClose(period: Period, deal: Deal, created: number, closed: number): void {
    if (deal.won === true) {
        period.wonValue += deal.actualValue ?? 0;
        period.wonCount += 1;
        if (Number.isFinite(created) && closed >= created) {
            period.cycleSum += Math.floor(closed / DAY) - Math.floor(created / DAY);
            period.cycleCount += 1;
        }
    } else if (deal.won === false) {
        period.lostValue += deal.value ?? 0;
        period.lostCount += 1;
    }
}

function averageCycle(period: Period): number {
    return period.cycleCount > 0 ? period.cycleSum / period.cycleCount : 0;
}

/**
 * Reference calculation for the currency regression fixtures introduced with #2267.
 * Pins calendar windows, Monday buckets, baseline eligibility, and UTC calendar-day cycles
 * independently of the server aggregates now used by My Work.
 */
export function personalDealKpis(deals: Deal[], currency: string, window: AnalyticsWindow, timezone: string): DealKpis {
    const current = emptyPeriod();
    const previous = emptyPeriod();
    const from = Date.parse(`${window.from}T00:00:00Z`);
    const previousFrom = new Date(from - analyticsWindowDays(window) * DAY).toISOString().slice(0, 10);
    const weeks = new Map<string, Period>();
    const end = Date.parse(`${window.to}T00:00:00Z`);
    for (let start = Date.parse(`${periodStartOf(window.from, "week")}T00:00:00Z`); start <= end; start += 7 * DAY) {
        weeks.set(new Date(start).toISOString().slice(0, 10), emptyPeriod());
    }

    const periodFor = (date: string) => date >= window.from && date <= window.to
        ? current : date >= previousFrom && date < window.from ? previous : null;

    for (const deal of deals) {
        if (deal.currency !== currency) continue;
        const created = parseMysqlDateTime(deal.createdAt);
        const closed = parseMysqlDateTime(deal.closedAt);
        if (Number.isFinite(created)) {
            const date = localIsoDate(created, timezone);
            const period = periodFor(date);
            if (period) {
                period.createdValue += deal.value ?? 0;
                period.createdCount += 1;
                if (period === current) {
                    const bucket = weeks.get(periodStartOf(date, "week"));
                    if (bucket) bucket.createdValue += deal.value ?? 0;
                }
            }
        }
        if (!Number.isFinite(closed)) continue;
        const date = localIsoDate(closed, timezone);
        const period = periodFor(date);
        if (!period) continue;
        recordClose(period, deal, created, closed);
        if (period === current) {
            const bucket = weeks.get(periodStartOf(date, "week"));
            if (bucket) recordClose(bucket, deal, created, closed);
        }
    }

    const buckets = [...weeks.values()];
    const previousClosed = previous.wonCount + previous.lostCount;
    return {
        wonRevenue: current.wonValue,
        wonRevenuePrev: previous.wonCount > 0 ? previous.wonValue : null,
        newPipeline: current.createdValue,
        newPipelinePrev: previous.createdCount > 0 ? previous.createdValue : null,
        wonCount: current.wonCount,
        lostCount: current.lostCount,
        wonValue: current.wonValue,
        lostValue: current.lostValue,
        wonCountPrev: previousClosed > 0 ? previous.wonCount : null,
        lostCountPrev: previousClosed > 0 ? previous.lostCount : null,
        avgCycleDays: averageCycle(current),
        avgCycleDaysPrev: previous.wonCount > 0 ? averageCycle(previous) : null,
        wonSeries: buckets.map((bucket) => bucket.wonValue),
        newPipelineSeries: buckets.map((bucket) => bucket.createdValue),
        winRateSeries: buckets.map((bucket) => bucket.wonCount + bucket.lostCount > 0
            ? bucket.wonCount * 100 / (bucket.wonCount + bucket.lostCount) : 0),
        avgCycleSeries: buckets.map(averageCycle),
    };
}
