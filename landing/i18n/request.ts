import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

import { LOCALE_COOKIE, resolveLocale, type Locale } from "./config";

export { defaultLocale, locales, LOCALE_COOKIE, type Locale } from "./config";

/**
 * The prelaunch deployment serves the landing body and the legal pages only, so it loads the two
 * namespaces those need rather than the full product set.
 */
const namespaces = ["common", "legal"] as const;

async function loadNamespace(locale: Locale, namespace: string): Promise<Record<string, unknown>> {
    const fragment = (await import(`../messages/${locale}/${namespace}.json`)).default;
    return typeof fragment === "object" && fragment !== null && !Array.isArray(fragment)
        ? (fragment as Record<string, unknown>)
        : {};
}

export default getRequestConfig(async () => {
    const store = await cookies();
    const locale = resolveLocale(store.get(LOCALE_COOKIE)?.value);
    const fragments = await Promise.all(namespaces.map((namespace) => loadNamespace(locale, namespace)));
    return { locale, messages: Object.assign({}, ...fragments) };
});
