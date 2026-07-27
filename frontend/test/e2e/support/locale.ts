import type { BrowserContext, Page } from "@playwright/test";
import { LOCALE_COOKIE, type Locale } from "@/i18n/config";
import { E2E_BASE_URL } from "../../../playwright.config";

/** A browser context, or a page whose context should carry the locale preference. */
export type LocaleTarget = BrowserContext | Page;

/**
 * Pins the application locale for every document the target loads from here on.
 *
 * Locale is not a URL segment in Connex: `i18n/request.ts` resolves it server-side from the
 * `NEXT_LOCALE` cookie, and a user's `locale` database column only reaches that cookie through a
 * client-side sync that deliberately skips contexts which already carry an explicit preference.
 * Setting the cookie is therefore the only deterministic way to drive a run in Japanese, and it
 * must happen *before* the navigation whose markup should be translated.
 *
 * @param target browser context, or a page whose context to pin
 * @param locale supported application locale
 */
export async function useLocale(target: LocaleTarget, locale: Locale): Promise<void> {
    const context = "context" in target ? target.context() : target;
    await context.addCookies([
        {
            name: LOCALE_COOKIE,
            value: locale,
            url: E2E_BASE_URL,
            sameSite: "Lax",
        },
    ]);
}
