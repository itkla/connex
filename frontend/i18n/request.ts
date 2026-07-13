import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

import { LOCALE_COOKIE, resolveLocale, type Locale } from "@/i18n/config";

export { defaultLocale, locales, LOCALE_COOKIE, type Locale } from "@/i18n/config";

const namespaces = [
    "common",
    "actions",
    "auth",
    "workspace",
    "organization",
    "account",
    "dashboard",
    "analytics",
    "reports",
    "activity",
    "me",
    "records",
    "companies",
    "contacts",
    "deals",
    "pipelines",
    "users",
    "map",
    "introductions",
    "calendar",
    "attachments",
    "admin",
    "notifications",
    "importExport",
    "legal",
    "docs",
] as const;

async function loadMessages(locale: Locale) {
    const fragments = await Promise.all(
        namespaces.map(async (ns) => {
            try {
                return (await import(`../messages/${locale}/${ns}.json`)).default as Record<string, unknown>;
            } catch {
                return {} as Record<string, unknown>;
            }
        }),
    );
    return fragments.reduce<Record<string, unknown>>((acc, fragment) => ({ ...acc, ...fragment }), {});
}

export default getRequestConfig(async () => {
    const requested = (await cookies()).get(LOCALE_COOKIE)?.value;
    const locale = resolveLocale(requested);

    return {
        locale,
        messages: await loadMessages(locale),
    };
});
