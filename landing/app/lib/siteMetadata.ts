import { locales, type Locale } from "@/i18n/config";

const OPEN_GRAPH_LOCALES: Record<Locale, string> = {
    en: "en_US",
    ja: "ja_JP",
};

/**
 * Open Graph locale tags for a rendered page: the locale it rendered in, and the other locales the
 * same address is served in. Derived from the supported locale set so a new locale cannot be added
 * without appearing here.
 * @param locale the locale the page rendered in
 * @returns `og:locale` and `og:locale:alternate` values
 */
export function openGraphLocales(locale: Locale): { locale: string; alternateLocale: string[] } {
    return {
        locale: OPEN_GRAPH_LOCALES[locale],
        alternateLocale: locales.flatMap((candidate) => (candidate === locale ? [] : [OPEN_GRAPH_LOCALES[candidate]])),
    };
}
