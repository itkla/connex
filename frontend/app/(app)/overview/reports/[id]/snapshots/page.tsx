import { notFound, redirect } from 'next/navigation';

/**
 * The bare snapshots path has no surface of its own: the report page already lists every frozen
 * snapshot as a chip strip. A reader reaches this path by truncating an emailed snapshot deep link,
 * so it sends them to that report rather than the hard 404 the missing segment produced.
 */
export default async function ReportSnapshotsPage({
    params,
}: {
    params: Promise<{ id: string }>;
}) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    redirect(`/overview/reports/${id}`);
}
