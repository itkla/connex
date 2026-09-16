import { LOCALE_COOKIE, type Locale } from "@/i18n/config";

/**
 * Persists the reader's language choice for a year; the request config reads it back.
 *
 * The product application's `app/lib/utils.ts` carries many unrelated helpers. This deployment keeps
 * only the one the shared landing components import, under the same module path, so those
 * components are copied from `frontend/` unchanged.
 * @param locale the chosen locale
 */
export function setLocaleCookie(locale: Locale) {
    document.cookie = `${LOCALE_COOKIE}=${locale};path=/;max-age=31536000;samesite=lax`;
}
