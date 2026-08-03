import { getTranslations } from 'next-intl/server';

import PermissionsUnavailable from '@/app/components/PermissionsUnavailable';

/**
 * Route-level state for a page whose permission check could not be evaluated, localized from the
 * shared `PermissionsUnavailable` namespace so a page that detects a failed lookup can return it in
 * one line. Wraps the presentational {@link PermissionsUnavailable}, which stays string-driven for
 * reuse from client trees.
 */
export default async function PermissionsUnavailablePage() {
    const t = await getTranslations('PermissionsUnavailable');
    return (
        <PermissionsUnavailable
            title={t('title')}
            body={t('body')}
            actions={[{ href: '/dashboard', label: t('home') }]}
        />
    );
}
