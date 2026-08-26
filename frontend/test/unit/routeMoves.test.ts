import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    ROUTE_MOVES,
    movedRouteTarget,
    type MovedRouteAddress,
} from "@/app/lib/routeMoves";
import { resolveShippedRoute, SHIPPED_APP_ROUTES } from "@/app/lib/routeManifest";
import { isProtectedPath } from "@/app/lib/protectedRoutes";
import { resolveBreadcrumbRoute, type BreadcrumbMessageKey } from "@/app/lib/breadcrumbRoutes";
import type { NavAccess } from "@/app/lib/navAccess";

const APP_ROOT = path.join(process.cwd(), "app", "(app)");

const ALL_ACCESS: NavAccess = {
    goals: true,
    auditLog: true,
    captureReviews: "enabled",
    campaigns: true,
    workflows: true,
    diagnostics: true,
};

function breadcrumbContext() {
    return {
        workspaceName: "Northstar",
        organizationName: "Black Mesa",
        organizationAccessible: true,
        navAccess: ALL_ACCESS,
        dynamicLabels: new Map<string, string>(),
        translate: (key: BreadcrumbMessageKey) => key,
        translateMessage: (key: string) => key,
    };
}

/** The shipped route patterns that sit under a retired prefix — the addresses the stubs must serve. */
const RETIRED_ROUTES: readonly string[] = SHIPPED_APP_ROUTES.filter((route) =>
    ROUTE_MOVES.some((move) => route === move.from || route.startsWith(`${move.from}/`)),
);

/** A retired pattern with its dynamic segments filled in, so it can be resolved as an address. */
function addressOf(route: string): MovedRouteAddress {
    return route.replace(/\[[^\]]+\]/g, "42") as MovedRouteAddress;
}

function pageSource(route: string): string {
    return readFileSync(
        path.join(APP_ROOT, ...route.split("/").filter((segment) => segment.length > 0), "page.tsx"),
        "utf8",
    );
}

/** Every `page.tsx` under the app shell, as a route pattern. */
function shippedPagePatterns(directory = APP_ROOT, segments: string[] = []): string[] {
    const patterns: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isFile() && entry.name === "page.tsx") patterns.push(`/${segments.join("/")}`);
        if (!entry.isDirectory()) continue;
        patterns.push(...shippedPagePatterns(
            path.join(directory, entry.name),
            [...segments, entry.name],
        ));
    }
    return patterns;
}

describe("the D13 route move keeps every retired address alive", () => {
    it("declares a move for each prefix the restructure retired", () => {
        expect(ROUTE_MOVES.map((move) => move.from)).toEqual([
            "/overview/analytics",
            "/overview/calendar",
            "/overview/introductions",
            "/overview/map",
            "/overview/reports",
            "/radar",
        ]);
    });

    it("keeps every retired route in the shipped manifest", () => {
        expect(RETIRED_ROUTES.length).toBe(12);
        expect([...RETIRED_ROUTES].sort()).toEqual(RETIRED_ROUTES);
    });

    it("serves every retired route from a page that redirects permanently", () => {
        const notRedirecting = RETIRED_ROUTES.filter(
            (route) => !/permanentRedirect\(/.test(pageSource(route)),
        );

        expect(notRedirecting, "a retired address must forward, never render").toEqual([]);
    });

    it("keeps the retired addresses on disk, so nothing 404s", () => {
        const missing = RETIRED_ROUTES.filter(
            (route) => !existsSync(path.join(
                APP_ROOT,
                ...route.split("/").filter((segment) => segment.length > 0),
                "page.tsx",
            )),
        );

        expect(missing).toEqual([]);
    });

    it.each(RETIRED_ROUTES)("forwards %s onto a shipped route", (route) => {
        const target = movedRouteTarget(addressOf(route), {});

        expect(resolveShippedRoute(target)).not.toBeNull();
    });

    it.each(RETIRED_ROUTES)("keeps %s behind the session gate", (route) => {
        expect(isProtectedPath(addressOf(route))).toBe(true);
        expect(isProtectedPath(movedRouteTarget(addressOf(route), {}))).toBe(true);
    });

    it("never forwards an address onto another forward", () => {
        const retired: readonly string[] = ROUTE_MOVES.map((move) => move.from);
        const onward = ROUTE_MOVES.filter((move) =>
            retired.some((from) => move.to === from || move.to.startsWith(`${from}/`)),
        );

        expect(onward).toEqual([]);
    });

    it("resolves the longest matching prefix rather than the first", () => {
        expect(movedRouteTarget("/overview/reports/7/snapshots/9", {}))
            .toBe("/insights/reports/7/snapshots/9");
        expect(movedRouteTarget("/radar", {})).toBe("/intelligence/radar");
    });

    it("refuses an address no move declares", () => {
        expect(() => movedRouteTarget("/records/contacts" as MovedRouteAddress, {})).toThrow();
    });

    it("carries the reader's query across whole, repeated keys included", () => {
        expect(movedRouteTarget("/overview/map", { companyId: ["1", "2"] }))
            .toBe("/intelligence/map?companyId=1&companyId=2");
        expect(movedRouteTarget("/overview/analytics", { owner: "3", range: "30d" }))
            .toBe("/insights/analytics?owner=3&range=30d");
        expect(movedRouteTarget("/radar", { family: "relationship_decay", when: "week" }))
            .toBe("/intelligence/radar?family=relationship_decay&when=week");
        expect(movedRouteTarget("/overview/calendar", { unknown: undefined }))
            .toBe("/activity/calendar");
    });

    it.each(RETIRED_ROUTES)("paints no breadcrumb trail for %s", (route) => {
        expect(resolveBreadcrumbRoute(addressOf(route), breadcrumbContext()))
            .toEqual({ kind: "redirect", crumbs: [] });
    });

    it("ships a page for every destination the moves name", () => {
        const patterns = new Set(shippedPagePatterns());
        const missing = ROUTE_MOVES.filter((move) => {
            const destination = movedRouteTarget(move.from, {});
            return ![...patterns].some(
                (pattern) => pattern === destination || pattern.startsWith(`${destination}/`),
            );
        });

        expect(missing).toEqual([]);
    });
});
