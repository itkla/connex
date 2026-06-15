import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

export const locales = ["en", "ja"] as const;
export const defaultLocale: Locale = "en";
export type Locale = (typeof locales)[number];
export const LOCALE_COOKIE = "NEXT_LOCALE";

function isLocale(value: string | undefined): value is Locale {
    return value !== undefined && (locales as readonly string[]).includes(value);
}

const namespaces = [
    "common",
    "auth",
    "dashboard",
    "analytics",
    "activity",
    "me",
    "records",
    "companies",
    "contacts",
    "deals",
    "pipelines",
    "users",
    "map",
    "calendar",
    "attachments",
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
    const locale: Locale = isLocale(requested) ? requested : defaultLocale;

    return {
        locale,
        messages: await loadMessages(locale),
    };
});
