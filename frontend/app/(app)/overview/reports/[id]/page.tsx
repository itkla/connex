import { notFound, permanentRedirect } from "next/navigation";

import { movedRouteTarget, type RouteSearchParams } from "@/app/lib/routeMoves";

/**
 * The retired address for one report document (#1323 WS4).
 *
 * Reports moved under Insights with the rest of the D13 restructure; this address survives so that
 * report links already shared, bookmarked, or stored keep resolving. It forwards permanently.
 *
 * The id is validated before it is forwarded rather than reflected as it arrived: a hand-crafted
 * segment has no business reaching a `Location` header, and the canonical page rejects the same
 * shapes anyway.
 */
export default async function LegacyReportPage({
    params,
    searchParams,
}: {
    params: Promise<{ id: string }>;
    searchParams: Promise<RouteSearchParams>;
}) {
    const { id } = await params;
    if (!/^\d+$/.test(id)) notFound();
    permanentRedirect(movedRouteTarget(`/overview/reports/${id}`, await searchParams));
}
