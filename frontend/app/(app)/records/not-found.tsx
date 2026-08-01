import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/**
 * 404 for a missing contact, company, deal or pipeline. Offers all three record lists
 * because a dead record link most often arrives from a stale bookmark or a share, and
 * the useful next step is the list the record belonged to.
 */
export default async function RecordsNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('records.body')}
            actions={[
                { href: '/records/contacts', label: t('records.contacts') },
                { href: '/records/companies', label: t('records.companies') },
                { href: '/records/deals', label: t('records.deals') },
            ]}
        />
    );
}
