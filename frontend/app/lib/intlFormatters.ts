const CACHE_LIMIT = 512;
const dates = new Map<string, Intl.DateTimeFormat>();
const numbers = new Map<string, Intl.NumberFormat>();
const relatives = new Map<string, Intl.RelativeTimeFormat>();

function formatterKey(locale: string | string[] | undefined, options: object): string {
    const entries: [string, unknown][] = Object.entries(options);
    return JSON.stringify([locale, entries.filter(([, value]) => value !== undefined)
        .sort(([left], [right]) => left.localeCompare(right))]);
}

function cached<T>(cache: Map<string, T>, key: string, create: () => T): T {
    const existing = cache.get(key);
    if (existing !== undefined) {
        cache.delete(key);
        cache.set(key, existing);
        return existing;
    }
    const formatter = create();
    cache.set(key, formatter);
    if (cache.size > CACHE_LIMIT) {
        const oldest = cache.keys().next().value;
        if (oldest !== undefined) cache.delete(oldest);
    }
    return formatter;
}

/** Reuses immutable date formatters by locale, time zone and options with bounded process memory. */
export function getDateTimeFormatter(locale?: string | string[], options: Intl.DateTimeFormatOptions = {}): Intl.DateTimeFormat {
    return cached(dates, formatterKey(locale, options), () => new Intl.DateTimeFormat(locale, options));
}

/** Reuses number formatters by locale and options. */
export function getNumberFormatter(locale?: string | string[], options: Intl.NumberFormatOptions = {}): Intl.NumberFormat {
    return cached(numbers, formatterKey(locale, options), () => new Intl.NumberFormat(locale, options));
}

/** Reuses relative-time formatters by locale and options across timeline rows. */
export function getRelativeTimeFormatter(locale?: string | string[], options: Intl.RelativeTimeFormatOptions = {}): Intl.RelativeTimeFormat {
    return cached(relatives, formatterKey(locale, options), () => new Intl.RelativeTimeFormat(locale, options));
}
