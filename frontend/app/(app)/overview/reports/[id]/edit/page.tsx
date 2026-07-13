import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import {
    getCurrentUserFromCookie,
    getPipelinesFromCookie,
    getReport,
    getTagsFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Tag, User } from '@/app/lib/types';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.editTitle'), description: t('metadata.editDescription') };
}

export default async function EditReportPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [report, pipelines, users, tags] = await Promise.all([
        getReport(id, init).catch(() => null),
        getPipelinesFromCookie(cookie),
        getUsers(init).catch((): User[] => []),
        getTagsFromCookie(cookie).catch((): Tag[] => []),
    ]);
    if (!report) notFound();

    return <ReportBuilderBoard initialReport={report} pipelines={pipelines} users={users} tags={tags} />;
}
