import { notFound, permanentRedirect } from "next/navigation";

import { movedRouteTarget, type RouteSearchParams } from "@/app/lib/routeMoves";

/**
 * The retired address for a report's bare snapshots path (#1323 WS4).
 *
 * A reader reaches this path by truncating an emailed snapshot deep link, so it forwards to the
 * canonical snapshots path, which in turn sends them on to the report that lists every snapshot.
 * Two hops is deliberate: keeping every legacy stub a pure prefix swap is what makes the route-move
 * manifest testable, and the destination already owns the truncated-deep-link rule.
 */
export default async function LegacyReportSnapshotsPage({
    params,
    searchParams,
}: {
    params: Promise<{ id: string }>;
    searchParams: Promise<RouteSearchParams>;
}) {
    const { id } = await params;
    if (!/^\d+$/.test(id)) notFound();
    permanentRedirect(movedRouteTarget(`/overview/reports/${id}/snapshots`, await searchParams));
}
