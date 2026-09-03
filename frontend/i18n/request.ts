import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

import { LOCALE_COOKIE, resolveLocale, type Locale } from "@/i18n/config";

export { defaultLocale, locales, LOCALE_COOKIE, type Locale } from "@/i18n/config";

const namespaces = [
    "common",
    "errors",
    "actions",
    "auth",
    "workspace",
    "settings",
    "workflow-operations",
    "organization",
    "account",
    "dashboard",
    "analytics",
    "reports",
    "assistant",
    "radar",
    "activity",
    "me",
    "records",
    "companies",
    "contacts",
    "deals",
    "pipelines",
    "products",
    "document-templates",
    "approval-policies",
    "campaigns",
    "users",
    "map",
    "introductions",
    "calendar",
    "attachments",
    "comments",
    "admin",
    "notifications",
    "importExport",
    "legal",
    "docs",
    "unsubscribe",
    "document-acceptance",
] as const;

function isMessagesFragment(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function loadMessages(locale: Locale) {
    const fragments = await Promise.all(
        namespaces.map(async (ns) => {
            try {
                const imported: unknown = await import(`../messages/${locale}/${ns}.json`);
                if (!isMessagesFragment(imported) || !isMessagesFragment(imported.default)) {
                    return {};
                }
                return imported.default;
            } catch {
                return {};
            }
        }),
    );
    return fragments.reduce<Record<string, unknown>>((acc, fragment) => ({ ...acc, ...fragment }), {});
}

export default getRequestConfig(async ({ locale: explicitLocale, requestLocale }) => {
    const requestedLocale = explicitLocale ?? await requestLocale;
    const cookieLocale = requestedLocale == null
        ? (await cookies()).get(LOCALE_COOKIE)?.value
        : undefined;
    const locale = resolveLocale(requestedLocale ?? cookieLocale);

    return {
        locale,
        messages: await loadMessages(locale),
    };
});
