import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import {
    getCurrentUserFromCookie,
    getPipelinesFromCookie,
    getReportTemplatesFromCookie,
    getTagsFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Tag, User } from '@/app/lib/types';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.newTitle'), description: t('metadata.newDescription') };
}

export default async function NewReportPage({
    searchParams,
}: {
    searchParams: Promise<{ template?: string }>;
}) {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');
    const { template: templateKey } = await searchParams;
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [templates, pipelines, users, tags] = await Promise.all([
        getReportTemplatesFromCookie(cookie),
        getPipelinesFromCookie(cookie),
        getUsers(init).catch((): User[] => []),
        getTagsFromCookie(cookie).catch((): Tag[] => []),
    ]);
    const template = templateKey ? templates.find((item) => item.key === templateKey) : undefined;

    return <ReportBuilderBoard initialTemplate={template} pipelines={pipelines} users={users} tags={tags} />;
}
