import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/** 404 for a missing document or file, offering the two library lists as a way back. */
export default async function LibraryNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('library.body')}
            actions={[
                { href: '/library/documents', label: t('library.documents') },
                { href: '/library/files', label: t('library.files') },
            ]}
        />
    );
}
