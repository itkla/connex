import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';
import { getTranslations } from 'next-intl/server';

import PageState from '@/app/components/PageState';

/**
 * Route-level state for a page whose permission check could not be evaluated because
 * the effective-permissions lookup itself failed.
 *
 * Exists to replace two dishonest alternatives. Treating an unreadable permission list
 * as "no permissions" either redirects the user off a page they are entitled to, or
 * renders a stripped read-only screen that misrepresents their role. This stays
 * fail-closed — the content is still withheld — while saying plainly that the check
 * failed rather than implying a verdict.
 */
export default async function PermissionsUnavailablePage() {
    const t = await getTranslations('PermissionsUnavailable');
    return (
        <PageState
            icon={ExclamationTriangleIcon}
            title={t('title')}
            body={t('body')}
            actions={[{ href: '/dashboard', label: t('home') }]}
        />
    );
}
