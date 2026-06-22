import { type Deal } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';

export type RangeKey = '30d' | '90d' | '12m';

export const RANGE_DAYS: Record<RangeKey, number> = {
    '30d': 30,
    '90d': 90,
    '12m': 365,
};

export const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;
export type ActivityType = (typeof ACTIVITY_TYPES)[number];

export const ACTIVITY_COLORS: Record<ActivityType, string> = {
    Call: 'var(--color-brand)',
    Email: '#0ea5e9',
    Meeting: '#10b981',
    Note: '#f59e0b',
    Other: 'var(--muted-foreground)',
};

const DAY = 86400000; // 1 day in milliseconds

export function normalizeActivityType(value?: string | null): ActivityType {
    const v = (value ?? '').trim();
    return (ACTIVITY_TYPES as readonly string[]).includes(v) ? (v as ActivityType) : 'Other';
}

export function isClosed(deal: Deal, now: number): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= now;
}

export type DeltaKind = 'pct' | 'pp';
export type KpiKey = 'wonRevenue' | 'newPipeline' | 'winRate' | 'avgCycle';

export type Kpi = {
    key: KpiKey;
    format: 'currency' | 'percent' | 'days';
    value: number;
    delta: number | null;
    deltaKind: DeltaKind;
    goodWhenUp: boolean;
    series: number[];
};

type Acc = {
    createdValue: number;
    wonValue: number;
    wonCount: number;
    lostCount: number;
    cycleSum: number;
    cycleCount: number;
};

const emptyAcc = (): Acc => ({
    createdValue: 0,
    wonValue: 0,
    wonCount: 0,
    lostCount: 0,
    cycleSum: 0,
    cycleCount: 0,
});

const winRateOf = (a: Acc) => (a.wonCount + a.lostCount > 0 ? a.wonCount / (a.wonCount + a.lostCount) : 0);
const cycleOf = (a: Acc) => (a.cycleCount > 0 ? a.cycleSum / a.cycleCount : 0);
const pctChange = (current: number, previous: number): number | null =>
    previous === 0 ? (current === 0 ? 0 : null) : (current - previous) / previous;

export function computeKpis(
    deals: Deal[],
    now: number,
    days: number,
): Kpi[] {
    const periodStart = now - days * DAY;
    const prevStart = now - 2 * days * DAY;
    const buckets = 12;
    const span = (now - periodStart) / buckets;
    const series = Array.from({ length: buckets }, emptyAcc);
    const current = emptyAcc();
    const previous = emptyAcc();

    const bucketIndex = (t: number) => Math.min(buckets - 1, Math.max(0, Math.floor((t - periodStart) / span)));

    for (const deal of deals) {
        const created = parseMysqlDateTime(deal.createdAt);
        const closed = parseMysqlDateTime(deal.closedAt);
        const value = deal.value ?? 0;
        const actual = deal.actualValue ?? 0;

        if (Number.isFinite(created)) {
            if (created >= periodStart && created <= now) {
                current.createdValue += value;
                series[bucketIndex(created)].createdValue += value;
            } else if (created >= prevStart && created < periodStart) {
                previous.createdValue += value;
            }
        }

        if (!Number.isFinite(closed) || closed > now) continue;
        const inCurrent = closed >= periodStart && closed <= now;
        const inPrevious = closed >= prevStart && closed < periodStart;
        if (!inCurrent && !inPrevious) continue;

        if (deal.won === true) {
            const cycleDays = Number.isFinite(created) && closed >= created ? (closed - created) / DAY : null;
            if (inCurrent) {
                current.wonValue += actual;
                current.wonCount += 1;
                const slot = series[bucketIndex(closed)];
                slot.wonValue += actual;
                slot.wonCount += 1;
                if (cycleDays != null) {
                    current.cycleSum += cycleDays;
                    current.cycleCount += 1;
                    slot.cycleSum += cycleDays;
                    slot.cycleCount += 1;
                }
            } else {
                previous.wonValue += actual;
                previous.wonCount += 1;
                if (cycleDays != null) {
                    previous.cycleSum += cycleDays;
                    previous.cycleCount += 1;
                }
            }
        } else if (deal.won === false) {
            if (inCurrent) {
                current.lostCount += 1;
                series[bucketIndex(closed)].lostCount += 1;
            } else {
                previous.lostCount += 1;
            }
        }
    }

    const closedBoth = current.wonCount + current.lostCount > 0 && previous.wonCount + previous.lostCount > 0;
    const cycleBoth = current.cycleCount > 0 && previous.cycleCount > 0;

    return [
        {
            key: 'wonRevenue',
            format: 'currency',
            value: current.wonValue,
            delta: pctChange(current.wonValue, previous.wonValue),
            deltaKind: 'pct',
            goodWhenUp: true,
            series: series.map((s) => s.wonValue),
        },
        {
            key: 'newPipeline',
            format: 'currency',
            value: current.createdValue,
            delta: pctChange(current.createdValue, previous.createdValue),
            deltaKind: 'pct',
            goodWhenUp: true,
            series: series.map((s) => s.createdValue),
        },
        {
            key: 'winRate',
            format: 'percent',
            value: winRateOf(current),
            delta: closedBoth ? winRateOf(current) - winRateOf(previous) : null,
            deltaKind: 'pp',
            goodWhenUp: true,
            series: series.map((s) => (s.wonCount + s.lostCount > 0 ? s.wonCount / (s.wonCount + s.lostCount) : 0)),
        },
        {
            key: 'avgCycle',
            format: 'days',
            value: cycleOf(current),
            delta: cycleBoth ? pctChange(cycleOf(current), cycleOf(previous)) : null,
            deltaKind: 'pct',
            goodWhenUp: false,
            series: series.map((s) => (s.cycleCount > 0 ? s.cycleSum / s.cycleCount : 0)),
        },
    ];
}

export type TimeBucket = { start: number; end: number; label: string };

export function buildTimeBuckets(range: RangeKey, now: number, locale: string): TimeBucket[] {
    const out: TimeBucket[] = [];
    if (range === '12m') {
        const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short' });
        const base = new Date(now);
        base.setDate(1);
        base.setHours(0, 0, 0, 0);
        for (let i = 11; i >= 0; i--) {
            const s = new Date(base.getFullYear(), base.getMonth() - i, 1);
            const e = new Date(base.getFullYear(), base.getMonth() - i + 1, 1);
            out.push({ start: s.getTime(), end: e.getTime(), label: monthLabel.format(s) });
        }
        return out;
    }
    const dayLabel = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' });
    const count = range === '30d' ? 6 : 9;
    const total = RANGE_DAYS[range] * DAY;
    const span = total / count;
    const startBase = now - total;
    for (let i = 0; i < count; i++) {
        const s = startBase + i * span;
        const e = startBase + (i + 1) * span;
        out.push({ start: s, end: e, label: dayLabel.format(new Date(s)) });
    }
    return out;
}