export const locales = ["en", "ja"] as const;
export const defaultLocale: Locale = "en";
export type Locale = (typeof locales)[number];
export const LOCALE_COOKIE = "NEXT_LOCALE";

function isLocale(value: string): value is Locale {
    return locales.some((locale) => locale === value);
}

/**
 * Resolves an application locale from an untrusted preference value.
 * @param value locale preference
 * @returns a supported locale, defaulting to English
 */
export function resolveLocale(value: string | null | undefined): Locale {
    return value !== undefined && value !== null && isLocale(value) ? value : defaultLocale;
}

/**
 * Resolves the effective application locale from a Cookie header.
 * @param cookieHeader serialized cookies
 * @returns a supported locale, defaulting to English
 */
export function localeFromCookieHeader(cookieHeader: string | null | undefined): Locale {
    const encodedLocale = cookieHeader
        ?.split(";")
        .map((part) => part.trim())
        .find((part) => part.startsWith(`${LOCALE_COOKIE}=`))
        ?.slice(LOCALE_COOKIE.length + 1);
    if (!encodedLocale) {
        return defaultLocale;
    }
    try {
        return resolveLocale(decodeURIComponent(encodedLocale));
    } catch {
        return defaultLocale;
    }
}
