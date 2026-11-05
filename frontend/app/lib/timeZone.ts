import { getDateTimeFormatter } from '@/app/lib/intlFormatters';

export const DEFAULT_TIME_ZONE = 'UTC';

/** Returns a supported zone, rejecting absent or legacy invalid profile values. */
export function validTimeZone(value?: string | null): string | null {
    if (!value?.trim()) return null;
    try {
        return getDateTimeFormatter('en', { timeZone: value.trim() }).resolvedOptions().timeZone;
    } catch {
        return null;
    }
}

/** Display instants in the profile zone, then the workspace zone, then UTC. */
export function resolveDisplayTimeZone(profile?: string | null, workspace?: string | null): string {
    return validTimeZone(profile) ?? validTimeZone(workspace) ?? DEFAULT_TIME_ZONE;
}

/** A stable calendar-day key for an instant in the selected display zone. */
export function calendarDayInTimeZone(value: number, timeZone: string): string {
    const parts = getDateTimeFormatter('en', {
        timeZone: resolveDisplayTimeZone(timeZone), year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(value);
    const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? '';
    return `${part('year')}-${part('month')}-${part('day')}`;
}

/** The civil hour of an instant, independent of the rendering host's zone. */
export function hourInTimeZone(value: number, timeZone?: string): number {
    return Number(getDateTimeFormatter('en', {
        timeZone: resolveDisplayTimeZone(timeZone), hour: 'numeric', hourCycle: 'h23',
    }).format(value));
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
