import { getLocale } from "next-intl/server";

import { localeFromAcceptLanguage, localeFromCookieHeader, locales, resolveLocale, type Locale } from "@/i18n/config";

/** Reads the server-rendered page language before falling back to an explicit cookie outside a page. */
export function browserApiLocale(): Locale {
    if (typeof document === "undefined") return resolveLocale(undefined);
    return locales.find((locale) => locale === document.documentElement?.lang)
        ?? localeFromCookieHeader(document.cookie);
}

async function serverApiLocale(init: RequestInit): Promise<Locale> {
    try {
        return resolveLocale(await getLocale());
    } catch {
        return localeFromCookieHeader(new Headers(init.headers).get("cookie"));
    }
}

/**
 * Uses the rendered browser language or the request's next-intl language for API calls.
 * An explicit server header lets the profile bootstrap establish locale without recursing into itself.
 */
export function apiRequestLocale(init: RequestInit = {}): Locale | Promise<Locale> {
    if (typeof document !== "undefined") return browserApiLocale();
    const explicit = new Headers(init.headers).get("Accept-Language");
    return explicit === null ? serverApiLocale(init) : localeFromAcceptLanguage(explicit);
}
