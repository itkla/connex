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
 * Selects a supported browser language by quality weight, preserving header order for ties.
 * Ignores unsupported, wildcard, malformed and zero-quality ranges; defaults to English.
 * @param acceptLanguage the untrusted Accept-Language request header
 * @returns the preferred English/Japanese language, including regional variants
 */
export function localeFromAcceptLanguage(acceptLanguage: string | null | undefined): Locale {
    let preferredLocale: Locale = defaultLocale;
    let preferredQuality = 0;

    for (const entry of acceptLanguage?.split(",") ?? []) {
        const match = entry.trim().match(/^([a-z]{1,8}(?:-[a-z0-9]{1,8})*|\*)(?:[ \t]*;[ \t]*q=(0(?:\.\d{0,3})?|1(?:\.0{0,3})?))?$/i);
        const language = match?.[1]?.split("-")[0]?.toLowerCase();
        if (!language || !isLocale(language)) continue;

        const quality = match?.[2] === undefined ? 1 : Number(match[2]);
        if (quality > preferredQuality) {
            preferredLocale = language;
            preferredQuality = quality;
        }
    }

    return preferredLocale;
}

/**
 * Resolves the effective application locale from a Cookie header.
 * @param cookieHeader serialized cookies
 * @returns a supported locale, defaulting to English
 */
export function localeFromCookieHeader(cookieHeader: string | null | undefined): Locale {
    return localePreferenceFromCookieHeader(cookieHeader) ?? defaultLocale;
}

/**
 * Reads an explicit supported locale preference from a Cookie header.
 * @param cookieHeader serialized cookies
 * @returns the explicit locale, or null when the cookie is absent or invalid
 */
export function localePreferenceFromCookieHeader(cookieHeader: string | null | undefined): Locale | null {
    const encodedLocale = cookieHeader
        ?.split(";")
        .map((part) => part.trim())
        .find((part) => part.startsWith(`${LOCALE_COOKIE}=`))
        ?.slice(LOCALE_COOKIE.length + 1);
    if (!encodedLocale) {
        return null;
    }
    try {
        const decoded = decodeURIComponent(encodedLocale);
        return isLocale(decoded) ? decoded : null;
    } catch {
        return null;
    }
}
