import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportDocumentBoard from '@/app/components/reports/ReportDocumentBoard';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
import { loadRecord } from '@/app/lib/recordAccess';
import {
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getReport,
    getReportSnapshots,
} from '@/app/lib/api';
import type { ReportSnapshotSummary } from '@/app/lib/types';
import { CrumbLabel } from '@/app/hooks/useNavTrail';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.documentTitle'), description: t('metadata.documentDescription') };
}

export default async function ReportPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) return <WorkspaceUnavailablePage />;
    const user = userResult.data;
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [reportAccess, snapshots, permissionsResult] = await Promise.all([
        loadRecord(() => getReport(id, init)),
        getReportSnapshots(id, init).catch((): ReportSnapshotSummary[] => []),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    if (reportAccess.kind === 'forbidden') return <AccessDeniedPage />;
    if (reportAccess.kind === 'missing') notFound();
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const report = reportAccess.record;
    const effectivePermissions = permissionsResult.data;
    if (report.config.widgets.some((widget) => widget.measure === 'attainment')
            && !effectivePermissions.includes('GOAL_READ')) {
        return <AccessDeniedPage />;
    }

    return (
        <>
            <CrumbLabel pathname={`/overview/reports/${id}`} value={report.name} />
            <ReportDocumentBoard
                definition={report}
                initialSnapshots={snapshots}
                canUpdateReports={effectivePermissions.includes('REPORT_UPDATE')}
                canDeleteReports={effectivePermissions.includes('REPORT_DELETE')}
                currentUserId={user.id}
                defaultTimezone={user.timezone}
            />
        </>
    );
}
