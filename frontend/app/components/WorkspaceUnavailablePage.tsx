import { getTranslations } from 'next-intl/server';

import PermissionsUnavailable from '@/app/components/PermissionsUnavailable';
import WorkspaceUnavailableRetry from '@/app/components/WorkspaceUnavailableRetry';

/**
 * Fail-closed route state for an unavailable workspace-membership lookup. It withholds the app
 * shell while distinguishing a transient check failure from a genuinely empty membership list.
 */
export default async function WorkspaceUnavailablePage() {
    const t = await getTranslations('WorkspaceUnavailable');

    return (
        <PermissionsUnavailable
            title={t('title')}
            body={t('body')}
            action={<WorkspaceUnavailableRetry label={t('retry')} pendingLabel={t('retrying')} />}
        />
    );
}
