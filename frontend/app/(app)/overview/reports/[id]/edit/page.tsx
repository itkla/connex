import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
import { loadRecord } from '@/app/lib/recordAccess';
import {
    getActiveWorkspaceMembersResultFromCookie,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getPipelines,
    getReport,
    getTags,
} from '@/app/lib/api';
import { CrumbLabel } from '@/app/hooks/useNavTrail';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.editTitle'), description: t('metadata.editDescription') };
}

export default async function EditReportPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) return <WorkspaceUnavailablePage />;
    const user = userResult.data;
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [reportAccess, pipelines, ownersResult, tags, permissionsResult] = await Promise.all([
        loadRecord(() => getReport(id, init)),
        getPipelines(init),
        getActiveWorkspaceMembersResultFromCookie(cookie),
        getTags(init),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    if (reportAccess.kind === 'forbidden') return <AccessDeniedPage />;
    if (reportAccess.kind === 'missing') notFound();
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const report = reportAccess.record;
    const canReadGoals = permissionsResult.data.includes('GOAL_READ');
    if (!canReadGoals && report.config.widgets.some((widget) => widget.measure === 'attainment')) {
        return <AccessDeniedPage />;
    }

    return (
        <>
            <CrumbLabel pathname={`/overview/reports/${id}`} value={report.name} />
            <ReportBuilderBoard
                initialReport={report}
                pipelines={pipelines}
                owners={ownersResult.ok ? ownersResult.data : []}
                ownersFailed={!ownersResult.ok}
                tags={tags}
                canReadGoals={canReadGoals}
            />
        </>
    );
}
