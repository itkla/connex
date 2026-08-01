import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportDocumentBoard from '@/app/components/reports/ReportDocumentBoard';
import {
    getCurrentUserFromCookie,
    getEffectivePermissionsFromCookie,
    getReport,
    getReportSnapshots,
} from '@/app/lib/api';
import type { ReportSnapshotSummary } from '@/app/lib/types';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.documentTitle'), description: t('metadata.documentDescription') };
}

export default async function ReportPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [report, snapshots, effectivePermissions] = await Promise.all([
        getReport(id, init).catch(() => null),
        getReportSnapshots(id, init).catch((): ReportSnapshotSummary[] => []),
        getEffectivePermissionsFromCookie(cookie),
    ]);
    if (!report) notFound();
    if (report.config.widgets.some((widget) => widget.measure === 'attainment')
            && !effectivePermissions.includes('GOAL_READ')) {
        redirect('/overview/reports');
    }

    return (
        <ReportDocumentBoard
            definition={report}
            initialSnapshots={snapshots}
            canUpdateReports={effectivePermissions.includes('REPORT_UPDATE')}
            canDeleteReports={effectivePermissions.includes('REPORT_DELETE')}
            currentUserId={user.id}
            defaultTimezone={user.timezone}
        />
    );
}
