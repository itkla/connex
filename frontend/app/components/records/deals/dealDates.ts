import { fixedOffsetSeconds, parseMysqlDateTime, toMysqlDateTime } from '@/app/lib/utils';

function reportingInstant(timestamp: number, timezone: string) {
    const offset = fixedOffsetSeconds(timezone);
    return { date: new Date(timestamp + (offset ?? 0) * 1000), timeZone: offset === null ? timezone : 'UTC' };
}

/** Formats a stored UTC close timestamp on its workspace reporting calendar. */
export function formatDealClosedAt(value: string | null | undefined, locale: string, timezone: string): string {
    const timestamp = parseMysqlDateTime(value);
    if (!Number.isFinite(timestamp)) return '—';
    const { date, timeZone } = reportingInstant(timestamp, timezone);
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone }).format(date);
}

function reportingParts(timestamp: number, timezone: string) {
    const { date, timeZone } = reportingInstant(timestamp, timezone);
    const parts = new Intl.DateTimeFormat('en-CA', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23', timeZone,
    }).formatToParts(date);
    const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? '';
    return {
        input: `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`,
        wallTimestamp: Date.UTC(Number(part('year')), Number(part('month')) - 1, Number(part('day')),
            Number(part('hour')), Number(part('minute')), Number(part('second'))),
    };
}

/** Displays a UTC close timestamp in a reporting-timezone datetime-local control. */
export function dealClosedAtInput(value: string | null | undefined, timezone: string): string {
    const timestamp = parseMysqlDateTime(value);
    return Number.isFinite(timestamp) ? reportingParts(timestamp, timezone).input : '';
}

/**
 * Converts an edited reporting wall time to UTC. Missing civil times are rejected; repeated times
 * keep the original instant when unchanged and otherwise select the earlier occurrence.
 */
export function dealClosedAtFromInput(value: string, timezone: string, previous?: string | null): string | null {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) || Number(value.slice(0, 4)) < 1000) return null;
    const anchor = Date.parse(`${value}Z`);
    if (!Number.isFinite(anchor)) return null;
    const prior = parseMysqlDateTime(previous);
    if (Number.isFinite(prior) && dealClosedAtInput(previous, timezone) === value) return toMysqlDateTime(prior);
    const offsets = new Set([-36, -12, 0, 12, 36].map((hours) => {
        const sample = anchor + hours * 3_600_000;
        return reportingParts(sample, timezone).wallTimestamp - sample;
    }));
    const candidates = [...offsets].filter(Number.isFinite).map((offset) => anchor - offset)
        .filter((timestamp) => dealClosedAtInput(new Date(timestamp).toISOString(), timezone) === value)
        .sort((left, right) => left - right);
    return candidates.length === 0 ? null : toMysqlDateTime(candidates[0]);
}
