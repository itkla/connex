import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/** 404 for a missing report definition or snapshot, offering the reports list as a way back. */
export default async function ReportsNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('reports.body')}
            actions={[{ href: '/insights/reports', label: t('reports.all') }]}
        />
    );
}
