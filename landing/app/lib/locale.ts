import { LOCALE_COOKIE, type Locale } from "@/i18n/config";

/** Persists the reader's language choice for a year; the request config reads it back. */
export function setLocaleCookie(locale: Locale) {
    document.cookie = `${LOCALE_COOKIE}=${locale};path=/;max-age=31536000;samesite=lax`;
}
