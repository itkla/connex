import { getTranslations } from 'next-intl/server';

import PermissionsUnavailable from '@/app/components/PermissionsUnavailable';
import WorkspaceUnavailableRetry from '@/app/components/WorkspaceUnavailableRetry';

/**
 * Fail-closed route state for an unavailable workspace-membership lookup. It withholds the app
 * shell while distinguishing a transient check failure from a genuinely empty membership list.
 *
 * Retry alone cannot be the only way out. A browser can hold a session cookie the server no longer
 * honours, and the route guard admits it on presence alone, so every retry re-reads the same
 * rejection and the state never resolves. Signing out is the one action that changes the inputs,
 * and it addresses `/auth/logout` rather than `/auth/login` because the guard bounces a sign-in
 * page reached with a lingering cookie straight back to the dashboard.
 */
export default async function WorkspaceUnavailablePage() {
    const t = await getTranslations('WorkspaceUnavailable');

    return (
        <PermissionsUnavailable
            title={t('title')}
            body={t('body')}
            action={<WorkspaceUnavailableRetry label={t('retry')} pendingLabel={t('retrying')} />}
            actions={[{ href: '/auth/logout', label: t('signOut'), variant: 'ghost' }]}
        />
    );
}
