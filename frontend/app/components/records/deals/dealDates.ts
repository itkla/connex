import { formatDate, parseDateForDisplay, parseDatetimeLocalValue, toDatetimeLocalValue, toMysqlDateTime } from '@/app/lib/utils';
import { DEFAULT_TIME_ZONE } from '@/app/lib/timeZone';

/** Formats UTC close instants in the display zone while preserving bare calendar dates. */
export function formatDealClosedAt(value: string | null | undefined, locale: string, timeZone = DEFAULT_TIME_ZONE): string {
    return Number.isFinite(parseDateForDisplay(value)) ? formatDate(value ?? undefined, locale, timeZone) : '—';
}

/** Uses the shared civil-input adapter for a close instant or calendar-only close date. */
export function dealClosedAtInput(value: string | null | undefined, timeZone = DEFAULT_TIME_ZONE): string {
    return toDatetimeLocalValue(value, timeZone);
}

/** Applies deal range validation and delegates DST/precision preservation to the shared converter. */
export function dealClosedAtFromInput(value: string, timeZone = DEFAULT_TIME_ZONE, previous?: string | null): string | null {
    if (Number(value.slice(0, 4)) < 1000) return null;
    const timestamp = parseDatetimeLocalValue(value, timeZone, previous);
    return Number.isFinite(timestamp) ? toMysqlDateTime(timestamp) : null;
}
