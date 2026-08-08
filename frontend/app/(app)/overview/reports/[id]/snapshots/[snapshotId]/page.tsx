import Link from 'next/link';
import { headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import { ArchiveBoxXMarkIcon, LockClosedIcon } from '@heroicons/react/24/outline';

import ReportDocumentBoard from '@/app/components/reports/ReportDocumentBoard';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
import {
    ApiError,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getReport,
    getReportSnapshot,
    getReportSnapshots,
} from '@/app/lib/api';
import type { ReportSnapshot, ReportSnapshotSummary } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { CrumbLabel } from '@/app/hooks/useNavTrail';

export async function generateMetadata() {
    const t = await getTranslations('Reports');
    return { title: t('metadata.snapshotTitle'), description: t('metadata.snapshotDescription') };
}

type SnapshotOutcome =
    | { status: 'ready'; snapshot: ReportSnapshot }
    | { status: 'gone' }
    | { status: 'forbidden' };

/** Separates "you may not read this snapshot" from "this snapshot no longer exists". */
async function loadSnapshot(
    id: number,
    snapshotId: number,
    init: RequestInit,
): Promise<SnapshotOutcome> {
    try {
        return { status: 'ready', snapshot: await getReportSnapshot(id, snapshotId, init) };
    } catch (error) {
        if (error instanceof ApiError && error.status === 403) {
            return { status: 'forbidden' };
        }
        return { status: 'gone' };
    }
}

/**
 * Renders one frozen snapshot by id. This is the destination of the scheduled-delivery email, so
 * it must show exactly the figures that were sent — the board is handed the loaded snapshot and
 * therefore never runs a live generation behind the reader's back.
 *
 * Retention prunes scheduled snapshots, so an emailed link outlives its snapshot by design. A
 * pruned snapshot renders a recoverable state pointing at the live report rather than a hard 404.
 *
 * Attainment figures need `GOAL_READ`, and the two ways that check can end are answered separately.
 * A viewer who is genuinely refused gets the route-level 403, matching the live report at
 * `../../page.tsx`, rather than a redirect that reads as ordinary navigation and leaves them with
 * nothing to act on. A viewer whose permissions could not be read at all gets the
 * permissions-unavailable state instead, because this is the destination of a scheduled-delivery
 * email: bouncing an entitled reader off an emailed link on a transient lookup failure, silently,
 * is the worst possible answer here. Both still withhold the board.
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
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) return <WorkspaceUnavailablePage />;
    const user = userResult.data;
    if (!user) redirect('/auth/login');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const t = await getTranslations('Reports');
    const [report, outcome, snapshots, permissionsResult] = await Promise.all([
        getReport(id, init).catch(() => null),
        loadSnapshot(id, snapshotId, init),
        getReportSnapshots(id, init).catch((): ReportSnapshotSummary[] => []),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    if (!report) notFound();

    if (outcome.status !== 'ready') {
        const forbidden = outcome.status === 'forbidden';
        const Icon = forbidden ? LockClosedIcon : ArchiveBoxXMarkIcon;
        return (
            <>
                <CrumbLabel pathname={`/overview/reports/${id}`} value={report.name} />
                <div className="min-h-full bg-background px-2 pb-12 pt-8">
                    <div className="mx-auto w-full max-w-[100rem]">
                        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
                            <Icon className="mx-auto size-8 text-muted-foreground" />
                            <h1 className="mt-4 text-lg font-semibold text-foreground">
                                {forbidden
                                    ? t('document.snapshotForbiddenTitle')
                                    : t('document.snapshotUnavailableTitle')}
                            </h1>
                            <p className="mx-auto mt-1 max-w-prose text-sm text-muted-foreground">
                                {forbidden
                                    ? t('document.snapshotForbiddenBody')
                                    : t('document.snapshotUnavailableBody')}
                            </p>
                            {forbidden ? null : (
                                <Button className="mt-6" variant="outline" asChild>
                                    <Link href={`/overview/reports/${id}`}>{t('document.viewLiveReport')}</Link>
                                </Button>
                            )}
                        </div>
                    </div>
                </div>
            </>
        );
    }

    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;
    const effectivePermissions = permissionsResult.data;
    const snapshot = outcome.snapshot;
    const showsAttainment = report.config.widgets.some((widget) => widget.measure === 'attainment')
        || snapshot.computedResult.widgets.some((widget) => widget.measure === 'attainment');
    if (showsAttainment && !effectivePermissions.includes('GOAL_READ')) {
        return <AccessDeniedPage />;
    }

    const listed = snapshots.some((summary) => summary.id === snapshot.id);
    const initialSnapshots: ReportSnapshotSummary[] = listed
        ? snapshots
        : [...snapshots, snapshot].sort((a, b) => (
            a.generatedAt === b.generatedAt ? b.id - a.id : (a.generatedAt < b.generatedAt ? 1 : -1)
        ));

    return (
        <>
            <CrumbLabel pathname={`/overview/reports/${id}`} value={report.name} />
            <ReportDocumentBoard
                definition={report}
                initialSnapshots={initialSnapshots}
                initialSnapshot={snapshot}
                canUpdateReports={effectivePermissions.includes('REPORT_UPDATE')}
                canDeleteReports={effectivePermissions.includes('REPORT_DELETE')}
                currentUserId={user.id}
                defaultTimezone={user.timezone}
            />
        </>
    );
}
