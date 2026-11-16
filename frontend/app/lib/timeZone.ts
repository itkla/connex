import { getDateTimeFormatter } from '@/app/lib/intlFormatters';

export const DEFAULT_TIME_ZONE = 'UTC';

/** Parses a legacy fixed-offset timezone ({@code UTC+9}, {@code +09:00}, …) into seconds, or null for IANA names. */
export function fixedOffsetSeconds(timezone: string): number | null {
    if (/^(?:Z|UTC|GMT|UT)$/i.test(timezone)) return 0;
    const match = /^(?:(?:UTC|GMT|UT))?([+-])(\d{1,2})(?::?(\d{2}))?(?::?(\d{2}))?$/i.exec(timezone);
    if (!match) return null;
    const hours = Number(match[2]);
    const minutes = Number(match[3] ?? '0');
    const seconds = Number(match[4] ?? '0');
    if (
        hours > 18
        || minutes > 59
        || seconds > 59
        || (hours === 18 && (minutes !== 0 || seconds !== 0))
    ) return null;
    const total = hours * 3600 + minutes * 60 + seconds;
    return match[1] === '-' ? -total : total;
}

/** Returns an Intl-supported zone, including canonicalized legacy minute-offset values. */
export function validTimeZone(value?: string | null): string | null {
    if (!value?.trim()) return null;
    try {
        const offset = fixedOffsetSeconds(value.trim());
        const absolute = Math.abs(offset ?? 0);
        const pad = (part: number) => String(part).padStart(2, '0');
        const timeZone = offset === 0 ? 'UTC'
            : offset !== null && offset % 60 === 0
                ? `${offset < 0 ? '-' : '+'}${pad(Math.floor(absolute / 3600))}:${pad(absolute % 3600 / 60)}`
                : value.trim();
        return getDateTimeFormatter('en', { timeZone }).resolvedOptions().timeZone;
    } catch {
        return null;
    }
}

/** Display instants in the profile zone, then the workspace zone, then UTC. */
export function resolveDisplayTimeZone(profile?: string | null, workspace?: string | null): string {
    return validTimeZone(profile) ?? validTimeZone(workspace) ?? DEFAULT_TIME_ZONE;
}

/** Adapts legacy fixed offsets, including seconds, before formatting through native Intl. */
export function dateTimeInTimeZone(timestamp: number, timeZone = DEFAULT_TIME_ZONE): { timestamp: number; timeZone: string } {
    const offset = fixedOffsetSeconds(timeZone);
    return offset === null
        ? { timestamp, timeZone: resolveDisplayTimeZone(timeZone) }
        : { timestamp: timestamp + offset * 1000, timeZone: 'UTC' };
}

/** A stable calendar-day key for an instant in the selected display zone. */
export function calendarDayInTimeZone(value: number, timeZone: string): string {
    const display = dateTimeInTimeZone(value, timeZone);
    const parts = getDateTimeFormatter('en', {
        timeZone: display.timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(display.timestamp);
    const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? '';
    return `${part('year')}-${part('month')}-${part('day')}`;
}

/** The civil hour of an instant, independent of the rendering host's zone. */
export function hourInTimeZone(value: number, timeZone?: string): number {
    const display = dateTimeInTimeZone(value, timeZone);
    return Number(getDateTimeFormatter('en', {
        timeZone: display.timeZone, hour: 'numeric', hourCycle: 'h23',
    }).format(display.timestamp));
}

/** Japanese greetings retain the complete name; English greetings use the given name. */
export function greetingName(displayName: string, locale: string): string {
    const name = displayName.trim();
    return locale.startsWith('ja') ? name : name.split(/\s+/)[0] ?? name;
}

/** A localized standard-time name alongside the canonical identifier used by the API. */
export function timeZoneLabel(value: string, locale: string): string {
    const timeZone = validTimeZone(value);
    if (!timeZone) return value;
    const name = getDateTimeFormatter(locale, { timeZone, timeZoneName: 'long' })
        .formatToParts(Date.UTC(2026, 0, 1)).find((part) => part.type === 'timeZoneName')?.value;
    return name ? `${value} · ${name}` : value;
}
