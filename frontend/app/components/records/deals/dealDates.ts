import { formatDate, parseDateForDisplay, parseDatetimeLocalValue, toDatetimeLocalValue, toMysqlDateTime } from '@/app/lib/utils';
import { DEFAULT_TIME_ZONE } from '@/app/lib/timeZone';

/** Formats UTC close instants on the reporting calendar while preserving bare calendar dates. */
export function formatDealClosedAt(value: string | null | undefined, locale: string, reportingTimeZone = DEFAULT_TIME_ZONE): string {
    return Number.isFinite(parseDateForDisplay(value)) ? formatDate(value ?? undefined, locale, reportingTimeZone) : '—';
}

/** Uses the shared civil-input adapter for a close instant or calendar-only close date. */
export function dealClosedAtInput(value: string | null | undefined, reportingTimeZone = DEFAULT_TIME_ZONE): string {
    return toDatetimeLocalValue(value, reportingTimeZone);
}

/** Applies deal range validation and delegates DST/precision preservation to the shared converter. */
export function dealClosedAtFromInput(value: string, reportingTimeZone = DEFAULT_TIME_ZONE, previous?: string | null): string | null {
    if (Number(value.slice(0, 4)) < 1000) return null;
    const timestamp = parseDatetimeLocalValue(value, reportingTimeZone, previous);
    return Number.isFinite(timestamp) ? toMysqlDateTime(timestamp) : null;
}
