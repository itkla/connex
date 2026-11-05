import { cache } from 'react';
import { headers } from 'next/headers';
import { getCurrentUserResultFromCookie, getMyWorkspacesResultFromCookie } from '@/app/lib/api';
import { resolveDisplayTimeZone, validTimeZone } from '@/app/lib/timeZone';

/** Resolves the authenticated display preference once per request, including public-page fallback. */
export const requestTimeZone = cache(async (): Promise<string> => {
    const cookie = (await headers()).get('cookie');
    if (!cookie || !/(?:^|;\s*)JSESSIONID=/.test(cookie)) return resolveDisplayTimeZone();
    const user = await getCurrentUserResultFromCookie(cookie);
    if (!user.ok || !user.data) return resolveDisplayTimeZone();
    const profile = validTimeZone(user.data.timezone);
    if (profile) return profile;
    const selection = await getMyWorkspacesResultFromCookie(cookie);
    const workspace = selection.ok
        ? selection.data.workspaces.find(({ id }) => id === selection.data.activeWorkspaceId)
        : undefined;
    return resolveDisplayTimeZone(null, workspace?.timezone);
});
