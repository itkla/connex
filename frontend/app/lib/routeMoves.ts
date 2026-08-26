import type { RouteSearchParams } from "@/app/lib/settingsRedirects";

export type { RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The route prefixes the D13 navigation restructure retired, paired with the address that serves
 * them now (#1323 WS4).
 *
 * The "Overview" grab-bag dissolved into the sections a reader actually names — Intelligence,
 * Activity and Insights — and Radar moved under the group the product vocabulary already puts it
 * in. Nothing about that move is allowed to break a link somebody already has, so every retired
 * prefix keeps a permanent redirect and this is the one place the pairing is declared: the stubs
 * read it, the breadcrumb registry reads it, and `test/unit/routeMoves.test.ts` walks it.
 */
export const ROUTE_MOVES = [
    { from: "/overview/analytics", to: "/insights/analytics" },
    { from: "/overview/calendar", to: "/activity/calendar" },
    { from: "/overview/introductions", to: "/intelligence/introductions" },
    { from: "/overview/map", to: "/intelligence/map" },
    { from: "/overview/reports", to: "/insights/reports" },
    { from: "/radar", to: "/intelligence/radar" },
] as const;

/** One retired route prefix from {@link ROUTE_MOVES}. */
export type MovedRoutePrefix = (typeof ROUTE_MOVES)[number]["from"];

/**
 * A retired address: a moved prefix, or any descendant of one.
 *
 * Spelling the descendants as a template literal is what makes a stub that names an address no move
 * declares a compile error rather than a redirect to `undefined` at request time.
 */
export type MovedRouteAddress = MovedRoutePrefix | `${MovedRoutePrefix}/${string}`;

/** Every retired prefix, for the registries that classify an address without resolving it. */
export const MOVED_ROUTE_PREFIXES: readonly MovedRoutePrefix[] = ROUTE_MOVES.map(
    (move) => move.from,
);

/**
 * Serializes resolved search parameters back into a query string.
 *
 * Every parameter survives, repeated keys included. A reader's link may carry anything — Radar's
 * filter family, the map's `companyId`, an analytics range somebody shared — and a permanent
 * redirect that edits the query is rewriting what someone else sent.
 *
 * @param searchParams - the resolved search parameters, as the App Router hands them over
 * @returns the query string including its leading `?`, or the empty string when there is none
 */
function queryString(searchParams: RouteSearchParams): string {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(searchParams)) {
        if (value === undefined) continue;
        if (Array.isArray(value)) for (const item of value) query.append(key, item);
        else query.append(key, value);
    }
    const serialized = query.toString();
    return serialized.length === 0 ? "" : `?${serialized}`;
}

/**
 * The permanent-redirect target for one retired address, query state included.
 *
 * The longest matching prefix wins, so a descendant of two overlapping moves resolves against the
 * more specific one. The remainder of the pathname is carried across untouched: a stub forwards the
 * address it was given rather than reconstructing it.
 *
 * @param pathname - the retired app-relative pathname being forwarded
 * @param searchParams - the resolved search parameters of the incoming request
 * @returns the app-relative target to forward to
 * @throws when the pathname matches no declared move, which {@link MovedRouteAddress} prevents
 */
export function movedRouteTarget(
    pathname: MovedRouteAddress,
    searchParams: RouteSearchParams,
): string {
    let best: (typeof ROUTE_MOVES)[number] | null = null;
    for (const move of ROUTE_MOVES) {
        if (pathname !== move.from && !pathname.startsWith(`${move.from}/`)) continue;
        if (best === null || move.from.length > best.from.length) best = move;
    }
    if (best === null) {
        throw new Error(`movedRouteTarget: ${pathname} matches no move in ROUTE_MOVES`);
    }
    return `${best.to}${pathname.slice(best.from.length)}${queryString(searchParams)}`;
}
