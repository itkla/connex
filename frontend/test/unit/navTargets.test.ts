import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = join(process.cwd(), "app");
const ROUTE_ROOTS = [join(APP_ROOT, "(app)"), APP_ROOT];

function isDynamicSegment(entry: string): boolean {
    return entry.startsWith("[") && entry.endsWith("]");
}

function resolveLiteralSegment(directory: string, segment: string): string | null {
    const literal = join(directory, segment);
    return existsSync(literal) ? literal : null;
}

/**
 * Reports whether a static in-app path resolves to a rendered route. Resolution is literal only: a
 * navigation target is a path the product declares, so a typo must fail rather than be absorbed by a
 * dynamic sibling segment (which would let `/records/deals/NOPE` masquerade as `[id]`).
 */
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

const paletteTargets = uniqueSorted([
    ...matchAll(read("app/lib/actions/seedActions.ts"), /navigateAction\(\s*"[^"]+",\s*"[^"]+",\s*"([^"]+)"/g),
    ...matchAll(read("app/components/actions/NavActionsBridge.tsx"), /router\.push\("([^"]+)"\)/g),
]);

const sidebarTargets = uniqueSorted(
    matchAll(read("app/components/Sidebar.tsx"), /href:\s*"(\/[^"]*)"/g),
);

const navTrailTargets = uniqueSorted(
    matchAll(read("app/hooks/useNavTrail.tsx"), /prefix:\s*'(\/[^']*)'/g),
);

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
            "/admin/logs",
            "/dashboard",
            "/docs",
            "/library/documents",
            "/library/files",
            "/library/tags",
            "/marketing/campaigns",
            "/me",
            "/notifications",
            "/organization/members",
            "/overview/analytics",
            "/overview/calendar",
            "/overview/introductions",
            "/overview/map",
            "/overview/reports",
            "/overview/reports/goals",
            "/records/approval-policies",
            "/records/companies",
            "/records/contacts",
            "/records/deals",
            "/records/pipelines",
            "/records/products",
            "/search",
            "/settings/members",
            "/users",
            "/workflows",
        ]);
    });

    it("rejects a path that only a dynamic sibling could absorb", () => {
        expect(routeExists("/records/deals/NOPE")).toBe(false);
        expect(routeExists("/records/deals")).toBe(true);
    });

    it.each(paletteTargets)("palette destination %s exists", (target) => {
        expect(routeExists(target)).toBe(true);
    });

    it.each(sidebarTargets)("sidebar destination %s exists", (target) => {
        expect(routeExists(target)).toBe(true);
    });

    it.each(navTrailTargets)("nav-trail route %s exists", (target) => {
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
