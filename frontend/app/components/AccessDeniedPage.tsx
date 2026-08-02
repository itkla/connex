import { getTranslations } from 'next-intl/server';

import AccessDenied from '@/app/components/AccessDenied';

/**
 * Route-level 403, localized from the shared `AccessDenied` namespace so a page that
 * detects a permission refusal can return it in one line. Wraps the presentational
 * {@link AccessDenied}, which stays string-driven for reuse from client trees.
 *
 * A route whose refusal has one specific cause may pass already-localized copy naming
 * the permission it needs, which is more useful than the generic wording; the offered
 * destinations stay shared so every route-level denial ends the same way.
 * @param title localized heading, defaulting to the shared wording
 * @param body localized explanation, defaulting to the shared wording
 */
export default async function AccessDeniedPage({
    title,
    body,
}: {
    title?: string;
    body?: string;
}) {
    const t = await getTranslations('AccessDenied');
    return (
        <AccessDenied
            title={title ?? t('title')}
            body={body ?? t('body')}
            actions={[
                { href: '/dashboard', label: t('home') },
                { href: '/auth/login', label: t('signIn'), variant: 'ghost' },
            ]}
        />
    );
}
