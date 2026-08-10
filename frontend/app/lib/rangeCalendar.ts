import { addDays, dayKeyOf, startOfDay, startOfMonth } from '@/app/lib/calendar';

/**
 * The three magnifications a range calendar can be read at. Each zoom selects at its own
 * granularity: a day cell picks one day, a month cell a whole month, a year cell a whole year.
 */
export type CalendarZoom = 'day' | 'month' | 'year';

/** An inclusive civil-date range. Both bounds are `YYYY-MM-DD`, so they compare lexicographically. */
export interface DateRange {
    from: string;
    to: string;
}

/** Number of year cells on one page of the year grid. */
export const YEAR_PAGE_SIZE = 12;

const DAY_MS = 86_400_000;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Strict `YYYY-MM-DD` to local-midnight Date. Returns null for malformed input and for
 * calendar-invalid dates such as `2026-02-30`, which the Date constructor would silently
 * roll into March. Local midnight — never UTC — so the value round-trips through
 * {@link dayKeyOf} unchanged in every timezone.
 */
export function parseDayKey(key: string): Date | null {
    if (!ISO_DAY.test(key)) return null;
    const year = Number(key.slice(0, 4));
    const month = Number(key.slice(5, 7));
    const day = Number(key.slice(8, 10));
    const parsed = new Date(year, month - 1, day);
    return parsed.getFullYear() === year && parsed.getMonth() === month - 1 && parsed.getDate() === day
        ? parsed
        : null;
}

/** January 1st of `date`'s year, at local midnight. */
export function startOfYear(date: Date): Date {
    return new Date(date.getFullYear(), 0, 1);
}

/** Last day of `date`'s month, at local midnight. */
export function endOfMonth(date: Date): Date {
    return new Date(date.getFullYear(), date.getMonth() + 1, 0);
}

/** December 31st of `date`'s year, at local midnight. */
export function endOfYear(date: Date): Date {
    return new Date(date.getFullYear(), 11, 31);
}

/**
 * First year of the fixed {@link YEAR_PAGE_SIZE} block containing `date`. Blocks are aligned to
 * multiples of the page size so paging forward and back always lands on the same boundaries.
 */
export function yearPageStart(date: Date): Date {
    return new Date(Math.floor(date.getFullYear() / YEAR_PAGE_SIZE) * YEAR_PAGE_SIZE, 0, 1);
}

/** The 12 first-of-month dates of `anchor`'s year, in calendar order. */
export function monthCellsForYear(anchor: Date): Date[] {
    return Array.from({ length: 12 }, (_, index) => new Date(anchor.getFullYear(), index, 1));
}

/** The {@link YEAR_PAGE_SIZE} January-1st dates of the year page containing `anchor`. */
export function yearCellsForPage(anchor: Date): Date[] {
    const start = yearPageStart(anchor);
    return Array.from(
        { length: YEAR_PAGE_SIZE },
        (_, index) => new Date(start.getFullYear() + index, 0, 1),
    );
}

/** The inclusive civil range covered by the period `date` falls in at `zoom`. */
export function periodRange(zoom: CalendarZoom, date: Date): DateRange {
    switch (zoom) {
        case 'day':
            return { from: dayKeyOf(date), to: dayKeyOf(date) };
        case 'month':
            return { from: dayKeyOf(startOfMonth(date)), to: dayKeyOf(endOfMonth(date)) };
        case 'year':
            return { from: dayKeyOf(startOfYear(date)), to: dayKeyOf(endOfYear(date)) };
    }
}

/**
 * The inclusive range spanning both anchors, each widened to its own period at `zoom`.
 * Order-independent: dragging right-to-left produces the same range as left-to-right.
 */
export function spanPeriods(zoom: CalendarZoom, anchor: Date, cursor: Date): DateRange {
    const first = periodRange(zoom, anchor);
    const second = periodRange(zoom, cursor);
    return {
        from: first.from <= second.from ? first.from : second.from,
        to: first.to >= second.to ? first.to : second.to,
    };
}

/**
 * Inclusive day count of a range, or 0 when a bound is unparseable or the range is reversed.
 * Rounds the millisecond difference because local-midnight dates straddling a daylight-saving
 * change are 23 or 25 hours apart, which truncation would miscount.
 */
export function rangeDays(range: DateRange): number {
    const from = parseDayKey(range.from);
    const to = parseDayKey(range.to);
    if (!from || !to || from.getTime() > to.getTime()) return 0;
    return Math.round((startOfDay(to).getTime() - startOfDay(from).getTime()) / DAY_MS) + 1;
}

/** True when extending a selection from `anchor` to `cursor` at `zoom` stays within `maxDays`. */
export function withinSpanLimit(
    zoom: CalendarZoom,
    anchor: Date,
    cursor: Date,
    maxDays: number,
): boolean {
    return rangeDays(spanPeriods(zoom, anchor, cursor)) <= maxDays;
}

/** True when the period `date` falls in at `zoom` overlaps `range` by at least one day. */
export function periodOverlaps(zoom: CalendarZoom, date: Date, range: DateRange): boolean {
    const period = periodRange(zoom, date);
    return period.from <= range.to && period.to >= range.from;
}

/** Daily magnitudes keyed by `YYYY-MM-DD`. Days with no entry count as zero. */
export type DaySeries = ReadonlyMap<string, number>;

/**
 * The bars one cell plots: a day contributes its own magnitude, a month one bar per day, a year
 * one bar per month. Every zoom therefore charts the same daily series at its own grain, so the
 * shape a user sees while zoomed out is the shape they find when they zoom in.
 */
export function cellSeries(zoom: CalendarZoom, date: Date, series: DaySeries): number[] {
    if (zoom === 'day') return [series.get(dayKeyOf(date)) ?? 0];
    if (zoom === 'month') {
        const days = endOfMonth(date).getDate();
        return Array.from(
            { length: days },
            (_, index) => series.get(dayKeyOf(new Date(date.getFullYear(), date.getMonth(), index + 1))) ?? 0,
        );
    }
    return Array.from({ length: 12 }, (_, month) => {
        const days = endOfMonth(new Date(date.getFullYear(), month, 1)).getDate();
        let total = 0;
        for (let day = 1; day <= days; day++) {
            total += series.get(dayKeyOf(new Date(date.getFullYear(), month, day))) ?? 0;
        }
        return total;
    });
}

/** Total magnitude across an inclusive range, used for the picker's "what am I selecting" readout. */
export function sumSeries(series: DaySeries, range: DateRange): number {
    const from = parseDayKey(range.from);
    const to = parseDayKey(range.to);
    if (!from || !to) return 0;
    let total = 0;
    for (let cursor = from; cursor.getTime() <= to.getTime(); cursor = addDays(cursor, 1)) {
        total += series.get(dayKeyOf(cursor)) ?? 0;
    }
    return total;
}

/**
 * Rounded-cap flags for the selection band under cell `index` of a `columns`-wide grid, given
 * each cell's membership in the range. A cap is drawn wherever the band meets a row edge or a
 * cell that is not selected, which keeps the band continuous across a row and closed at its ends.
 */
export function bandCaps(
    member: readonly boolean[],
    index: number,
    columns: number,
): { start: boolean; end: boolean } {
    if (!member[index]) return { start: false, end: false };
    return {
        start: index % columns === 0 || !member[index - 1],
        end: index % columns === columns - 1 || !member[index + 1],
    };
}
