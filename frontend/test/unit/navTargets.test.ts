import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { BREADCRUMB_STATIC_ROUTE_PATHS } from "@/app/lib/breadcrumbRoutes";
import { SETTINGS_PALETTE_REGISTRATIONS } from "@/app/lib/actions/settingsNavigationActions";
import { settingsEntryPointDestinations } from "@/app/lib/settingsEntryPoints";

const APP_ROOT = join(process.cwd(), "app");
const ROUTE_ROOTS = [join(APP_ROOT, "(app)"), APP_ROOT];

function isDynamicSegment(entry: string): boolean {
    return entry.startsWith("[") && entry.endsWith("]");
}

function resolveLiteralSegment(directory: string, segment: string): string | null {
    const literal = join(directory, segment);
    return existsSync(literal) ? literal : null;
}

function routeExists(path: string): boolean {
    const segments = path.split("/").filter(Boolean);
    return ROUTE_ROOTS.some((root) => {
        let current = root;
        if (!existsSync(current)) return false;
        for (const segment of segments) {
            const next = resolveLiteralSegment(current, segment);
            if (next === null) return false;
            current = next;
        }
        return existsSync(join(current, "page.tsx"));
    });
}

function read(relativePath: string): string {
    return readFileSync(join(process.cwd(), relativePath), "utf8");
}

function uniqueSorted(values: readonly string[]): string[] {
    return [...new Set(values)].sort();
}

function matchAll(source: string, pattern: RegExp): string[] {
    return [...source.matchAll(pattern)].map((match) => match[1]);
}

/**
 * The address part of a navigation target.
 *
 * A consolidated settings destination is addressed at the section that absorbed the job, so its
 * target carries a fragment that no route on disk answers for. The fragment is the page's business;
 * what this suite checks is that the page exists.
 */
function routePath(target: string): string {
    return target.split("#")[0];
}

/**
 * Every destination the command palette can push.
 *
 * The literal ones are still scraped from the two registries that spell a route; the settings ones
 * come from the generated registrations, which resolve their address from the settings manifest and
 * therefore have no literal to scrape (#1340 PR 7).
 */
const paletteTargets = uniqueSorted([
    ...matchAll(read("app/lib/actions/seedActions.ts"), /navigateAction\(\s*"[^"]+",\s*"[^"]+",\s*"([^"]+)"/g),
    ...matchAll(read("app/components/actions/NavActionsBridge.tsx"), /router\.push\("([^"]+)"\)/g),
    ...SETTINGS_PALETTE_REGISTRATIONS.map((registration) => registration.href),
]);

/** Likewise for the sidebar and the user menu, whose settings rows name a manifest entry. */
const sidebarTargets = uniqueSorted([
    ...matchAll(read("app/components/Sidebar.tsx"), /href:\s*"(\/[^"]*)"/g),
    ...settingsEntryPointDestinations("sidebar").map((destination) => destination.href),
    ...settingsEntryPointDestinations("avatar-menu").map((destination) => destination.href),
]);

const recordDetailBases = uniqueSorted(
    matchAll(read("app/lib/actions/seedActions.ts"), /^\s{4}\w+:\s*"([^"]+)",$/gm),
);

describe("navigation targets resolve to real routes", () => {
    it("registers exactly the expected palette destinations", () => {
        expect(paletteTargets).toEqual([
            "/account",
            "/account/connections/reviews",
            "/activity/all",
            "/activity/notes",
            "/activity/tasks",
            "/dashboard",
            "/docs",
            "/library/documents",
            "/library/files",
            "/library/tags",
            "/marketing/campaigns",
            "/me",
            "/notifications",
            "/overview/analytics",
            "/overview/calendar",
            "/overview/introductions",
            "/overview/map",
            "/overview/reports",
            "/overview/reports/goals",
            "/radar",
            "/records/companies",
            "/records/contacts",
            "/records/deals",
            "/records/pipelines",
            "/records/products",
            "/search",
            "/settings",
            "/settings/organization/identity#administrators",
            "/settings/workspace/audit-diagnostics#audit",
            "/settings/workspace/audit-diagnostics#diagnostics",
            "/settings/workspace/crm#approval-policies",
            "/settings/workspace/people#directory",
            "/workflows",
        ]);
    });

    it("rejects a path that only a dynamic sibling could absorb", () => {
        expect(routeExists("/records/deals/NOPE")).toBe(false);
        expect(routeExists("/records/deals")).toBe(true);
    });

    it.each(paletteTargets)("palette destination %s exists", (target) => {
        expect(routeExists(routePath(target))).toBe(true);
    });

    it.each(sidebarTargets)("sidebar destination %s exists", (target) => {
        expect(routeExists(routePath(target))).toBe(true);
    });

    it.each(BREADCRUMB_STATIC_ROUTE_PATHS)("breadcrumb route %s exists", (target) => {
        expect(routeExists(target)).toBe(true);
    });

    it.each(recordDetailBases)("record detail base %s has a dynamic child route", (base) => {
        const segments = base.split("/").filter(Boolean);
        const resolved = ROUTE_ROOTS.map((root) =>
            segments.reduce<string | null>(
                (current, segment) => (current === null ? null : resolveLiteralSegment(current, segment)),
                existsSync(root) ? root : null,
            ),
        ).find((candidate) => candidate !== null && existsSync(candidate));

        expect(resolved).toBeTruthy();
        const children = readdirSync(resolved as string, { withFileTypes: true })
            .filter((entry) => entry.isDirectory() && isDynamicSegment(entry.name))
            .filter((entry) => existsSync(join(resolved as string, entry.name, "page.tsx")));
        expect(children.length).toBeGreaterThan(0);
    });
});
