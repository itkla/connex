import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/**
 * In-shell 404 for the authenticated app. Renders inside the app layout so the sidebar
 * and workspace context survive a dead link, and covers every app segment without a
 * more specific `not-found.tsx` of its own.
 */
export default async function AppNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('body')}
            actions={[{ href: '/dashboard', label: t('home') }]}
        />
    );
}
