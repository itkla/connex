import { permanentRedirect } from "next/navigation";

import { movedRouteTarget, type RouteSearchParams } from "@/app/lib/routeMoves";

/**
 * The retired address for the new-report composer, before Reports moved under Insights (#1323 WS4).
 *
 * The D13 navigation restructure dissolved the Overview grab-bag, and this address survives only so
 * that links already sent, bookmarked, or stored keep resolving. It forwards permanently.
 *
 * The target is read from the route-move manifest rather than spelled here, so a destination that
 * moves again takes its redirects with it, and the reader's query string survives the hop whole.
 */
export default async function LegacyNewReportPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(movedRouteTarget("/overview/reports/new", await searchParams));
}
