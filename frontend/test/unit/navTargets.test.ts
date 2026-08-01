import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = join(process.cwd(), "app");
const ROUTE_ROOTS = [join(APP_ROOT, "(app)"), APP_ROOT];

function isDynamicSegment(entry: string): boolean {
    return entry.startsWith("[") && entry.endsWith("]");
}

function resolveSegment(directory: string, segment: string): string | null {
    const literal = join(directory, segment);
    if (existsSync(literal)) return literal;
    const dynamic = readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory() && isDynamicSegment(entry.name))
        .map((entry) => entry.name)
        .sort()[0];
    return dynamic ? join(directory, dynamic) : null;
}

/** Reports whether an in-app path resolves to a rendered route under the App Router tree. */
function routeExists(path: string): boolean {
    const segments = path.split("/").filter(Boolean);
    return ROUTE_ROOTS.some((root) => {
        let current = root;
        if (!existsSync(current)) return false;
        for (const segment of segments) {
            const next = resolveSegment(current, segment);
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
    it("registers the expected number of palette destinations", () => {
        expect(paletteTargets.length).toBeGreaterThanOrEqual(28);
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
                (current, segment) => (current === null ? null : resolveSegment(current, segment)),
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
