import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import {
    getActiveWorkspaceMembersResultFromCookie,
    getCurrentUserFromCookie,
    getEffectivePermissionsFromCookie,
    getPipelinesFromCookie,
    getReportTemplatesFromCookie,
    getTagsFromCookie,
} from '@/app/lib/api';
import type { Tag } from '@/app/lib/types';

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
    const [templates, pipelines, ownersResult, tags, effectivePermissions] = await Promise.all([
        getReportTemplatesFromCookie(cookie),
        getPipelinesFromCookie(cookie),
        getActiveWorkspaceMembersResultFromCookie(cookie),
        getTagsFromCookie(cookie).catch((): Tag[] => []),
        getEffectivePermissionsFromCookie(cookie),
    ]);
    const canReadGoals = effectivePermissions.includes('GOAL_READ');
    const template = templateKey
        ? templates.find((item) => item.key === templateKey && (canReadGoals || item.key !== 'quota-attainment'))
        : undefined;

    return (
        <ReportBuilderBoard
            initialTemplate={template}
            pipelines={pipelines}
            owners={ownersResult.ok ? ownersResult.data : []}
            ownersFailed={!ownersResult.ok}
            tags={tags}
            canReadGoals={canReadGoals}
        />
    );
}
