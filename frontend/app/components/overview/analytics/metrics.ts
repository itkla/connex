import { type Deal } from '@/app/lib/types';
import { fixedOffsetSeconds, parseMysqlDateTime } from '@/app/lib/utils';

export type RollingRangeKey = '30d' | '90d' | '12m';
export type RangeKey = RollingRangeKey | 'custom';
export type Granularity = 'day' | 'week' | 'month';

export const ROLLING_RANGE_KEYS: readonly RollingRangeKey[] = ['30d', '90d', '12m'];

/** Narrows an arbitrary URL value to a known analytics range key. */
export function isRangeKey(value: string | null): value is RangeKey {
    return value != null && (
        (ROLLING_RANGE_KEYS as readonly string[]).includes(value)
        || value === 'custom'
    );
}

/** Narrows an arbitrary URL value to a known series granularity. */
export function isGranularity(value: string | null): value is Granularity {
    return value === 'day' || value === 'week' || value === 'month';
}

/**
 * Granularities offered per range, bounded so no combination explodes into an
 * oversized series (mirrors the server's bucket cap — e.g. no day-grain 12 months).
 */
export const RANGE_GRANULARITIES: Record<RangeKey, readonly Granularity[]> = {
    '30d': ['day', 'week'],
    '90d': ['day', 'week', 'month'],
    '12m': ['week', 'month'],
    custom: ['week', 'month'],
};

export const DEFAULT_GRANULARITY: Record<RangeKey, Granularity> = {
    '30d': 'week',
    '90d': 'week',
    '12m': 'month',
    custom: 'week',
};

/** Returns the bounded grains available for a range and its explicit window. */
export function granularitiesForRange(range: RangeKey, window?: AnalyticsWindow): readonly Granularity[] {
    if (range !== 'custom' || !window) return RANGE_GRANULARITIES[range];
    return analyticsWindowDays(window) <= 120
        ? ['day', 'week', 'month']
        : RANGE_GRANULARITIES.custom;
}

/** Returns {@code choice} when the range supports it, otherwise the range's default grain. */
export function clampGranularity(
    range: RangeKey,
    choice: Granularity | null,
    window?: AnalyticsWindow,
): Granularity {
    return choice != null && granularitiesForRange(range, window).includes(choice)
        ? choice
        : DEFAULT_GRANULARITY[range];
}

export const RANGE_DAYS: Record<RollingRangeKey, number> = {
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

export function buildTimeBuckets(range: RollingRangeKey, now: number, locale: string): TimeBucket[] {
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

/** Inclusive local-date window (ISO {@code yyyy-MM-dd}) an analytics range resolves to. */
export type AnalyticsWindow = { from: string; to: string };

function parseIsoDate(value: string): Date | null {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const parsed = new Date(`${value}T00:00:00Z`);
    return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
        ? parsed
        : null;
}

/** Inclusive number of local calendar days represented by an analytics window. */
export function analyticsWindowDays(window: AnalyticsWindow): number {
    const from = parseIsoDate(window.from);
    const to = parseIsoDate(window.to);
    if (!from || !to || from.getTime() > to.getTime()) return 0;
    return Math.floor((to.getTime() - from.getTime()) / DAY) + 1;
}

/** Longest custom window the backend accepts, in inclusive calendar days. */
export const MAX_CUSTOM_RANGE_DAYS = 731;

/** Parses a complete, ordered custom range within the backend's 731-day window limit. */
export function parseCustomAnalyticsWindow(from: string | null, to: string | null): AnalyticsWindow | null {
    if (!from || !to) return null;
    const window = { from, to };
    const days = analyticsWindowDays(window);
    return days >= 1 && days <= MAX_CUSTOM_RANGE_DAYS ? window : null;
}

function utcDateFromParts(year: number, monthIndex: number, day: number): Date {
    return new Date(Date.UTC(year, monthIndex, day));
}

function todayAnchor(now: number, timezone: string): Date {
    const offsetSeconds = fixedOffsetSeconds(timezone);
    if (offsetSeconds != null) {
        const shifted = new Date(now + offsetSeconds * 1000);
        return utcDateFromParts(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate());
    }
    try {
        const iso = new Intl.DateTimeFormat('en-CA', {
            timeZone: timezone,
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
        }).format(new Date(now));
        const parsed = new Date(`${iso}T00:00:00Z`);
        if (!Number.isNaN(parsed.getTime())) return parsed;
    } catch {
        return todayAnchor(now, 'UTC');
    }
    return todayAnchor(now, 'UTC');
}

function toIsoDate(anchor: Date): string {
    return anchor.toISOString().slice(0, 10);
}

function addDaysUtc(anchor: Date, days: number): Date {
    return utcDateFromParts(anchor.getUTCFullYear(), anchor.getUTCMonth(), anchor.getUTCDate() + days);
}

function mondayOfWeek(anchor: Date): Date {
    return addDaysUtc(anchor, -((anchor.getUTCDay() + 6) % 7));
}

/**
 * Resolves an analytics range key to its inclusive local-date window in the viewer's
 * timezone. Rolling ranges end today; a valid custom window passes through unchanged.
 */
export function resolveAnalyticsWindow(
    range: RangeKey,
    now: number,
    timezone: string,
    customWindow?: AnalyticsWindow | null,
): AnalyticsWindow {
    const today = todayAnchor(now, timezone);
    const year = today.getUTCFullYear();
    const month = today.getUTCMonth();
    switch (range) {
        case '30d':
            return { from: toIsoDate(addDaysUtc(today, -29)), to: toIsoDate(today) };
        case '90d':
            return { from: toIsoDate(addDaysUtc(today, -89)), to: toIsoDate(today) };
        case '12m':
            return { from: toIsoDate(utcDateFromParts(year, month - 11, 1)), to: toIsoDate(today) };
        case 'custom':
            return parseCustomAnalyticsWindow(customWindow?.from ?? null, customWindow?.to ?? null)
                ?? resolveAnalyticsWindow('90d', now, timezone);
    }
}

function periodStartAnchor(anchor: Date, granularity: Granularity): Date {
    if (granularity === 'day') return anchor;
    if (granularity === 'week') return mondayOfWeek(anchor);
    return utcDateFromParts(anchor.getUTCFullYear(), anchor.getUTCMonth(), 1);
}

function nextPeriodAnchor(anchor: Date, granularity: Granularity): Date {
    if (granularity === 'day') return addDaysUtc(anchor, 1);
    if (granularity === 'week') return addDaysUtc(anchor, 7);
    return utcDateFromParts(anchor.getUTCFullYear(), anchor.getUTCMonth() + 1, 1);
}

/** ISO start date of the calendar period (Monday week / month 1st / same day) containing {@code isoDate}. */
export function periodStartOf(isoDate: string, granularity: Granularity): string {
    return toIsoDate(periodStartAnchor(new Date(`${isoDate}T00:00:00Z`), granularity));
}

/** Today's local calendar date ({@code yyyy-MM-dd}) in the given IANA timezone. */
export function localIsoDate(now: number, timezone: string): string {
    return toIsoDate(todayAnchor(now, timezone));
}

/**
 * Extends a rolling window's end forward by three grain periods so forward-looking
 * series keep a horizon; an explicit custom window stays exact.
 */
export function projectionWindow(
    window: AnalyticsWindow,
    range: RangeKey,
    granularity: Granularity,
): AnalyticsWindow {
    if (range === 'custom') return window;
    let anchor = periodStartAnchor(new Date(`${window.to}T00:00:00Z`), granularity);
    for (let i = 0; i < 3; i++) anchor = nextPeriodAnchor(anchor, granularity);
    const extended = toIsoDate(addDaysUtc(nextPeriodAnchor(anchor, granularity), -1));
    return { from: window.from, to: extended < window.to ? window.to : extended };
}

/** Formats a bucket's start date as a compact axis tick for its grain and locale. */
export function formatPeriodTick(
    periodStart: string,
    granularity: Granularity,
    locale: string,
    withYear = false,
): string {
    const date = new Date(`${periodStart}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return periodStart;
    if (granularity === 'month') {
        return new Intl.DateTimeFormat(locale, {
            month: 'short',
            ...(withYear ? { year: 'numeric' as const } : {}),
            timeZone: 'UTC',
        }).format(date);
    }
    return new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(date);
}

/** Formats a bucket's start date for tooltips: full date for day/week grains, month + year for months. */
export function formatPeriodTooltipDate(periodStart: string, granularity: Granularity, locale: string): string {
    const date = new Date(`${periodStart}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return periodStart;
    if (granularity === 'month') {
        return new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', timeZone: 'UTC' }).format(date);
    }
    return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        timeZone: 'UTC',
    }).format(date);
}

const MAX_CLIENT_BUCKETS = 400;

/**
 * Enumerates the calendar buckets (browser-local epochs) covering an analytics window,
 * for the panels that still bucket already-fetched client data. Weeks start Monday,
 * matching the server-side bucketing.
 */
export function buildCalendarBuckets(
    window: AnalyticsWindow,
    granularity: Granularity,
    locale: string,
): TimeBucket[] {
    const parse = (iso: string): Date | null => {
        const [y, m, d] = iso.split('-').map(Number);
        return Number.isInteger(y) && Number.isInteger(m) && Number.isInteger(d)
            ? new Date(y, m - 1, d)
            : null;
    };
    const from = parse(window.from);
    const to = parse(window.to);
    if (!from || !to || from.getTime() > to.getTime()) return [];
    const localAnchor = (utc: Date) => new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
    const toUtcAnchor = (local: Date) =>
        utcDateFromParts(local.getFullYear(), local.getMonth(), local.getDate());
    const out: TimeBucket[] = [];
    let cursor = periodStartAnchor(toUtcAnchor(from), granularity);
    const endExclusive = nextPeriodAnchor(periodStartAnchor(toUtcAnchor(to), granularity), granularity);
    while (cursor.getTime() < endExclusive.getTime() && out.length < MAX_CLIENT_BUCKETS) {
        const next = nextPeriodAnchor(cursor, granularity);
        out.push({
            start: localAnchor(cursor).getTime(),
            end: localAnchor(next).getTime(),
            label: formatPeriodTick(toIsoDate(cursor), granularity, locale),
        });
        cursor = next;
    }
    return out;
}
