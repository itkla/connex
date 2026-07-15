import { updateMyLocale } from "@/app/lib/api";
import { setLocaleCookie } from "@/app/lib/utils";
import type { Locale } from "@/i18n/config";

let updateQueue: Promise<void> = Promise.resolve();

/**
 * Persists an authenticated locale preference before updating the UI cookie.
 * @param locale supported locale selected by the user
 * @returns the locale confirmed by the backend
 */
export function persistAuthenticatedLocale(locale: Locale): Promise<Locale> {
    const request = updateQueue.then(async () => {
        const updated = await updateMyLocale(locale);
        if (updated.locale !== locale) {
            throw new Error("locale-update-mismatch");
        }
        setLocaleCookie(updated.locale);
        return updated.locale;
    });
    updateQueue = request.then(
        () => undefined,
        () => undefined,
    );
    return request;
}
