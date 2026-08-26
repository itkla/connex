import { notFound, permanentRedirect } from "next/navigation";

import { movedRouteTarget, type RouteSearchParams } from "@/app/lib/routeMoves";

/**
 * The retired address for one frozen report snapshot (#1323 WS4).
 *
 * This is the address printed as an absolute URL in scheduled-report emails that were already
 * delivered before Reports moved under Insights. Those messages cannot be edited, so this stub is
 * permanent: it is the only thing standing between them and a 404.
 *
 * Both ids are validated before they reach the `Location` header.
 */
export default async function LegacyReportSnapshotPage({
    params,
    searchParams,
}: {
    params: Promise<{ id: string; snapshotId: string }>;
    searchParams: Promise<RouteSearchParams>;
}) {
    const { id, snapshotId } = await params;
    if (!/^\d+$/.test(id) || !/^\d+$/.test(snapshotId)) notFound();
    permanentRedirect(
        movedRouteTarget(`/overview/reports/${id}/snapshots/${snapshotId}`, await searchParams),
    );
}
