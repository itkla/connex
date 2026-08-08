import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
import {
    getActiveWorkspaceMembersResultFromCookie,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getPipelines,
    getReportTemplates,
    getTags,
} from '@/app/lib/api';

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
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) return <WorkspaceUnavailablePage />;
    const user = userResult.data;
    if (!user) redirect('/auth/login');
    const { template: templateKey } = await searchParams;
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [templates, pipelines, ownersResult, tags, permissionsResult] = await Promise.all([
        getReportTemplates(init),
        getPipelines(init),
        getActiveWorkspaceMembersResultFromCookie(cookie),
        getTags(init),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const canReadGoals = permissionsResult.data.includes('GOAL_READ');
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
