import { getTranslations } from 'next-intl/server';

import AccessDenied from '@/app/components/AccessDenied';

/**
 * Route-level 403, localized from the shared `AccessDenied` namespace so a page that
 * detects a permission refusal can return it in one line. Wraps the presentational
 * {@link AccessDenied}, which stays string-driven for reuse from client trees.
 */
export default async function AccessDeniedPage() {
    const t = await getTranslations('AccessDenied');
    return (
        <AccessDenied
            title={t('title')}
            body={t('body')}
            actions={[
                { href: '/dashboard', label: t('home') },
                { href: '/auth/login', label: t('signIn'), variant: 'ghost' },
            ]}
        />
    );
}
