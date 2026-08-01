import { getTranslations } from 'next-intl/server';

import NotFoundState from '@/app/components/NotFoundState';

/** 404 for a missing task, note or activity, offering each activity list as a way back. */
export default async function ActivityNotFound() {
    const t = await getTranslations('NotFound');
    return (
        <NotFoundState
            title={t('title')}
            body={t('activity.body')}
            actions={[
                { href: '/activity/tasks', label: t('activity.tasks') },
                { href: '/activity/notes', label: t('activity.notes') },
                { href: '/activity/all', label: t('activity.activities') },
            ]}
        />
    );
}
