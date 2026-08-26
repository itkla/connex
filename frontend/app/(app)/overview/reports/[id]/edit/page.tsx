import { notFound, permanentRedirect } from "next/navigation";

import { movedRouteTarget, type RouteSearchParams } from "@/app/lib/routeMoves";

/**
 * The retired address for the report editor (#1323 WS4).
 *
 * Reports moved under Insights with the rest of the D13 restructure; this address survives so that
 * editor links already shared or bookmarked keep resolving. It forwards permanently, with the id
 * validated before it reaches the `Location` header.
 */
export default async function LegacyReportEditPage({
    params,
    searchParams,
}: {
    params: Promise<{ id: string }>;
    searchParams: Promise<RouteSearchParams>;
}) {
    const { id } = await params;
    if (!/^\d+$/.test(id)) notFound();
    permanentRedirect(movedRouteTarget(`/overview/reports/${id}/edit`, await searchParams));
}
