import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';

import ReportDocumentBoard from '@/app/components/reports/ReportDocumentBoard';
import {
    getCurrentUserFromCookie,
    getEffectivePermissionsFromCookie,
    getReport,
    getReportSnapshot,
    getReportSnapshots,
} from '@/app/lib/api';
import type { ReportSnapshotSummary } from '@/app/lib/types';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.snapshotTitle'), description: t('metadata.snapshotDescription') };
}

/**
 * Renders one frozen snapshot by id. This is the destination of the scheduled-delivery email, so
 * it must show exactly the figures that were sent — the board is handed the loaded snapshot and
 * therefore never runs a live generation behind the reader's back.
 */
export default async function ReportSnapshotPage({
    params,
}: {
    params: Promise<{ id: string; snapshotId: string }>;
}) {
    const { id: rawId, snapshotId: rawSnapshotId } = await params;
    const id = Number(rawId);
    const snapshotId = Number(rawSnapshotId);
    if (!Number.isInteger(id) || id < 1) notFound();
    if (!Number.isInteger(snapshotId) || snapshotId < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [report, snapshot, snapshots, effectivePermissions] = await Promise.all([
        getReport(id, init).catch(() => null),
        getReportSnapshot(id, snapshotId, init).catch(() => null),
        getReportSnapshots(id, init).catch((): ReportSnapshotSummary[] => []),
        getEffectivePermissionsFromCookie(cookie),
    ]);
    if (!report || !snapshot) notFound();
    const showsAttainment = report.config.widgets.some((widget) => widget.measure === 'attainment')
        || snapshot.computedResult.widgets.some((widget) => widget.measure === 'attainment');
    if (showsAttainment && !effectivePermissions.includes('GOAL_READ')) {
        redirect('/overview/reports');
    }

    const listed = snapshots.some((summary) => summary.id === snapshot.id);
    const initialSnapshots: ReportSnapshotSummary[] = listed ? snapshots : [snapshot, ...snapshots];

    return (
        <ReportDocumentBoard
            definition={report}
            initialSnapshots={initialSnapshots}
            initialSnapshot={snapshot}
            canUpdateReports={effectivePermissions.includes('REPORT_UPDATE')}
            canDeleteReports={effectivePermissions.includes('REPORT_DELETE')}
            currentUserId={user.id}
            defaultTimezone={user.timezone}
        />
    );
}
