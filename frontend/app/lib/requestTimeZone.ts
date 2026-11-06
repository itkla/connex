import { cache } from 'react';
import { headers } from 'next/headers';
import { getCurrentUserResultFromCookie, getMyWorkspacesResultFromCookie } from '@/app/lib/api';
import { resolveDisplayTimeZone, validTimeZone } from '@/app/lib/timeZone';
import { locales, type Locale } from '@/i18n/config';

/** Resolves language and time zone from one profile read per request, falling back on unavailable reads. */
export const requestPreferences = cache(async (): Promise<{ locale: Locale | null; timeZone: string }> => {
    const fallback = { locale: null, timeZone: resolveDisplayTimeZone() };
    const cookie = (await headers()).get('cookie');
    if (!cookie || !/(?:^|;\s*)JSESSIONID=/.test(cookie)) return fallback;
    const user = await getCurrentUserResultFromCookie(cookie).catch(() => null);
    if (!user?.ok || !user.data) return fallback;
    const profile = user.data;
    const locale = locales.find((supportedLocale) => supportedLocale === profile.locale) ?? null;
    const timeZone = validTimeZone(profile.timezone);
    if (timeZone) return { locale, timeZone };
    const selection = await getMyWorkspacesResultFromCookie(cookie).catch(() => null);
    const workspace = selection?.ok
        ? selection.data.workspaces.find(({ id }) => id === selection.data.activeWorkspaceId)
        : undefined;
    return { locale, timeZone: resolveDisplayTimeZone(null, workspace?.timezone) };
});

/** Resolves the authenticated display preference once per request, including public-page fallback. */
export const requestTimeZone = cache(async (): Promise<string> => {
    return (await requestPreferences()).timeZone;
});
