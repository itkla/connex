import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/**
 * Application-wide 404. Next.js renders the root `not-found` for any URL that matches
 * no route at all, so this one is reached by logged-out visitors too — it offers the
 * public start page rather than a workspace destination that would bounce them
 * through login.
 */
export default async function RootNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('site.body')}
            actions={[{ href: '/', label: t('site.home') }]}
        />
    );
}
