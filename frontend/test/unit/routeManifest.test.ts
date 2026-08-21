import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    matchesShippedRoute,
    resolveShippedRoute,
    routeAllowsQueryParam,
    SHIPPED_APP_ROUTES,
    SHIPPED_ROUTE_QUERY_PARAMS,
} from "@/app/lib/routeManifest";
import {
    ACTIVITY_URL_KEY,
    COMMENT_URL_KEY,
    DEEP_LINK_URL_KEYS,
    NOTE_URL_KEY,
    PIPELINE_EDIT_URL_KEY,
    TASK_URL_KEY,
} from "@/app/hooks/listStateUrl";
import {
    companyDealsHref,
    contactDealsHref,
    DEAL_COMPANY_FILTER_KEY,
    DEAL_CONTACT_FILTER_KEY,
    DEAL_PIPELINE_FILTER_KEY,
    DEAL_RISK_FILTER_KEY,
    DEAL_RISK_LEVELS,
    DEAL_STAGE_FILTER_KEY,
    DEAL_STATUS_FILTER_KEY,
    pipelineDealsHref,
    riskDealsHref,
    stageDealsHref,
} from "@/app/components/records/deals/dealLinks";
import { RADAR_FAMILY_FILTER_KEY, radarFamilyHref } from "@/app/components/radar/radarLinks";
import { INTRODUCTIONS_PATH } from "@/app/components/introductions/introductionLinks";
import { buildEvents, type CalendarEvent, type CalendarEventKind } from "@/app/lib/calendar";
import { recentRecordHref } from "@/app/lib/recentRecords";
import { savedViewHref } from "@/app/lib/savedViewLink";
import { SAVED_VIEW_URL_KEY } from "@/app/hooks/listStateUrl";
import type { Activity, Deal, Note, SavedView, SavedViewRecordType, Task } from "@/app/lib/types";

const SHARED_MANIFEST_PATH = path.join(
    process.cwd(),
    "..",
    "backend",
    "src",
    "test",
    "resources",
    "frontend-route-manifest.json",
);

type SharedManifest = {
    params: Record<string, string[]>;
    routes: string[];
};

function isJsonObject(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isStringArrayRecord(value: unknown): value is Record<string, string[]> {
    return isJsonObject(value) && Object.values(value).every(isStringArray);
}

function isStringArray(value: unknown): value is string[] {
    return Array.isArray(value) && value.every((entry) => typeof entry === "string");
}

function readSharedManifest(): SharedManifest {
    const parsed: unknown = JSON.parse(readFileSync(SHARED_MANIFEST_PATH, "utf8"));
    if (!isJsonObject(parsed)) throw new Error("shared route manifest is not an object");
    const { params, routes } = parsed;
    if (!isStringArrayRecord(params)) throw new Error("shared route manifest params must map routes to string arrays");
    if (!isStringArray(routes)) throw new Error("shared route manifest routes must be an array of strings");
    return { params, routes };
}

function appRouterRoutes(directory: string, segments: string[] = []): string[] {
    const routes: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isFile() && entry.name === "page.tsx") {
            routes.push(`/${segments.join("/")}`);
            continue;
        }
        if (!entry.isDirectory()) continue;
        const isRouteGroup = entry.name.startsWith("(") && entry.name.endsWith(")");
        const nextSegments = isRouteGroup ? segments : [...segments, entry.name];
        routes.push(...appRouterRoutes(path.join(directory, entry.name), nextSegments));
    }
    return routes;
}

const filesystemRoutes = appRouterRoutes(path.join(process.cwd(), "app", "(app)")).sort();
const shippedRoutes: readonly string[] = SHIPPED_APP_ROUTES;
const sharedManifest = readSharedManifest();

function calendarEvent(kind: CalendarEventKind): CalendarEvent {
    const task: Task = {
        id: 11,
        description: "Send the renewal quote",
        completed: false,
        status: "todo",
        position: 0,
        dueDate: "2026-03-04",
        assignedToId: 1,
        createdAt: "2026-03-01 09:00:00",
        updatedAt: "2026-03-01 09:00:00",
    };
    const activity: Activity = {
        id: 12,
        type: "Call",
        subject: "Renewal call",
        createdById: 1,
        timestamp: "2026-03-04 10:00:00",
    };
    const deal: Deal = {
        id: 13,
        name: "Renewal",
        value: 1000,
        actualValue: 0,
        currency: "USD",
        pipeline: 1,
        stage: 1,
        position: 0,
        company: 7,
        expectedCloseDate: "2026-03-04",
        createdAt: "2026-03-01 09:00:00",
        updatedAt: "2026-03-01 09:00:00",
    };
    const note: Note = {
        id: 14,
        content: "Signed off on scope",
        author: 1,
        createdAt: "2026-03-04 11:00:00",
        updatedAt: "2026-03-04 11:00:00",
    };
    const events = buildEvents({ tasks: [task], activities: [activity], deals: [deal], notes: [note] });
    const event = events.find((candidate) => candidate.kind === kind);
    if (!event) throw new Error(`calendar produced no ${kind} event`);
    return event;
}

function queryOf(href: string): URLSearchParams {
    return new URLSearchParams(href.slice(href.indexOf("?") + 1));
}

describe("shipped route manifest", () => {
    it("registers every App Router page under the authenticated shell", () => {
        expect(filesystemRoutes.length).toBeGreaterThan(0);
        expect(filesystemRoutes.filter((route) => !shippedRoutes.includes(route))).toEqual([]);
    });

    it("registers no route the app does not ship", () => {
        expect(shippedRoutes.filter((route) => !filesystemRoutes.includes(route))).toEqual([]);
    });

    it("stays in sync with the manifest the backend validates action URLs against", () => {
        expect(sharedManifest.routes).toEqual([...SHIPPED_APP_ROUTES]);
        expect(sharedManifest.params).toEqual({ ...SHIPPED_ROUTE_QUERY_PARAMS });
    });

    it("assigns every canonical deep-link parameter to at least one consuming route", () => {
        const registered = Object.values(SHIPPED_ROUTE_QUERY_PARAMS).flat();

        expect([...new Set(registered)].sort()).toEqual(
            [...new Set(Object.values(DEEP_LINK_URL_KEYS))].sort(),
        );
    });

    it("prefers a literal segment over a dynamic sibling", () => {
        expect(resolveShippedRoute("/overview/reports/new")).toBe("/overview/reports/new");
        expect(resolveShippedRoute("/overview/reports/42")).toBe("/overview/reports/[id]");
    });

    it("rejects an href no shipped route serves", () => {
        expect(resolveShippedRoute("/records/pipelines/7")).toBeNull();
        expect(resolveShippedRoute("/activity/deals")).toBeNull();
        expect(resolveShippedRoute("/introductions")).toBeNull();
        expect(resolveShippedRoute("https://example.test/records/deals")).toBeNull();
    });

    it("ignores the query string and fragment when resolving a route", () => {
        expect(resolveShippedRoute(`/activity/tasks?${TASK_URL_KEY}=9`)).toBe("/activity/tasks");
        expect(resolveShippedRoute("/records/deals#top")).toBe("/records/deals");
    });

    it("allows query parameters only on the route that consumes them", () => {
        expect(routeAllowsQueryParam(`/activity/tasks?${TASK_URL_KEY}=9`, TASK_URL_KEY)).toBe(true);
        expect(routeAllowsQueryParam(`/activity/tasks?${NOTE_URL_KEY}=9`, NOTE_URL_KEY)).toBe(false);
        expect(routeAllowsQueryParam(`/records/pipelines?${PIPELINE_EDIT_URL_KEY}=9`, PIPELINE_EDIT_URL_KEY)).toBe(true);
        expect(routeAllowsQueryParam(`/records/deals/9?${PIPELINE_EDIT_URL_KEY}=7`, PIPELINE_EDIT_URL_KEY)).toBe(false);
        expect(routeAllowsQueryParam(`/records/pipelines?${TASK_URL_KEY}=9`, TASK_URL_KEY)).toBe(false);
        expect(routeAllowsQueryParam("/records/pipelines?unknown=9", "unknown")).toBe(false);
        expect(routeAllowsQueryParam("/not-shipped?task=9", TASK_URL_KEY)).toBe(false);
    });
});

describe("deep-link producers emit the consumers' canonical params", () => {
    it("links a calendar task to the tasks browser's task param", () => {
        const href = calendarEvent("task").href;

        expect(resolveShippedRoute(href)).toBe("/activity/tasks");
        expect(queryOf(href).get(TASK_URL_KEY)).toBe("11");
    });

    it("links a calendar note to the notes browser's note param", () => {
        const href = calendarEvent("note").href;

        expect(resolveShippedRoute(href)).toBe("/activity/notes");
        expect(queryOf(href).get(NOTE_URL_KEY)).toBe("14");
    });

    it("links calendar activities and deals to their record routes", () => {
        expect(resolveShippedRoute(calendarEvent("activity").href)).toBe("/activity/activities/[id]");
        expect(resolveShippedRoute(calendarEvent("deal").href)).toBe("/records/deals/[id]");
    });

    it("links a Deals stat tile to the deals browser filtered by company", () => {
        const href = companyDealsHref(7) ?? "";

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_COMPANY_FILTER_KEY)).toBe("7");
    });

    it("renders no deals link for a contact with no company rather than linking the whole browser", () => {
        expect(companyDealsHref(null)).toBeUndefined();
        expect(companyDealsHref(undefined)).toBeUndefined();
    });

    it("links a contact's deal count to the deals where that contact is a stakeholder", () => {
        const href = contactDealsHref(9);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_CONTACT_FILTER_KEY)).toBe("9");
    });

    it("links a pipeline's deal count to the deals browser filtered by pipeline", () => {
        const href = pipelineDealsHref(3);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_PIPELINE_FILTER_KEY)).toBe("3");
    });

    it("links a risk severity to the deals browser filtered by that severity", () => {
        const href = riskDealsHref(["high"]);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_RISK_FILTER_KEY)).toBe("high");
    });

    it("links an at-risk total to every flagged severity rather than the whole browser", () => {
        const href = riskDealsHref([]);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_RISK_FILTER_KEY)).toBe([...DEAL_RISK_LEVELS].join(","));
    });

    it("links a funnel stage to that stage's open deals", () => {
        const href = stageDealsHref(3, 8);
        const query = queryOf(href);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(query.get(DEAL_PIPELINE_FILTER_KEY)).toBe("3");
        expect(query.get(DEAL_STAGE_FILTER_KEY)).toBe("8");
        expect(query.get(DEAL_STATUS_FILTER_KEY)).toBe("open");
    });

    it("links a decay figure to Radar filtered to the decay family", () => {
        const href = radarFamilyHref("relationship_decay");

        expect(resolveShippedRoute(href)).toBe("/radar");
        expect(queryOf(href).get(RADAR_FAMILY_FILTER_KEY)).toBe("relationship_decay");
    });

    it("links a record's intro path to the Introductions surface", () => {
        expect(resolveShippedRoute(INTRODUCTIONS_PATH)).toBe("/overview/introductions");
    });

    it.each(["company", "person", "deal"] as const)(
        "links a pinned %s saved view to its records browser",
        (recordType: SavedViewRecordType) => {
            const view: SavedView = {
                id: 5,
                workspaceId: 2,
                recordType,
                name: "Warm accounts",
                visibility: "private",
                ownerUserId: 1,
                ownedByCurrentUser: true,
                config: {},
                position: 0,
                pinned: true,
                pinPosition: 0,
                default: false,
                createdAt: "2026-03-01 09:00:00",
                updatedAt: "2026-03-01 09:00:00",
            };
            const href = savedViewHref(view);

            expect(matchesShippedRoute(href)).toBe(true);
            expect(queryOf(href).get(SAVED_VIEW_URL_KEY)).toBe("2:5");
        },
    );

    it.each(["company", "person", "deal"] as const)(
        "links a recent %s to its record detail route",
        (recordType) => {
            expect(matchesShippedRoute(recentRecordHref(recordType, 42))).toBe(true);
        },
    );

    it("resolves every record-scoped notification deep link the backend emits", () => {
        const links = [
            `/records/contacts/42?${TASK_URL_KEY}=9`,
            `/records/companies/42?${ACTIVITY_URL_KEY}=9`,
            `/records/deals/42?${COMMENT_URL_KEY}=9`,
            `/activity/notes?${NOTE_URL_KEY}=9`,
        ];

        for (const href of links) {
            expect(matchesShippedRoute(href)).toBe(true);
            for (const key of queryOf(href).keys()) {
                expect(routeAllowsQueryParam(href, key)).toBe(true);
            }
        }
    });
});

const DYNAMIC_SEGMENT = "__dynamic__";

const APP_SHELL_TOP_SEGMENTS = new Set(
    shippedRoutes.map((route) => route.split("/").filter(Boolean)[0]).filter(Boolean),
);

/** Recursively lists every TypeScript source file under a directory. */
function sourceFiles(directory: string): string[] {
    const files: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            files.push(...sourceFiles(full));
        } else if (/\.tsx?$/.test(entry.name)) {
            files.push(full);
        }
    }
    return files;
}

const HREF_PATTERNS = [
    /href=\{?["'`](\/[^"'`\s]*)/g,
    /router\.(?:push|replace|prefetch)\(\s*["'`](\/[^"'`\s]*)/g,
];

/**
 * Every literal or template-literal href target found in the app's source, with `${…}` holes
 * normalized to a wildcard segment marker. Multi-segment holes and non-app-shell paths are filtered
 * by the assertion, not here.
 */
function literalAppHrefs(): Array<{ file: string; href: string }> {
    const hits: Array<{ file: string; href: string }> = [];
    for (const file of sourceFiles(path.join(process.cwd(), "app"))) {
        const source = readFileSync(file, "utf8");
        for (const pattern of HREF_PATTERNS) {
            for (const match of source.matchAll(pattern)) {
                hits.push({
                    file: path.relative(process.cwd(), file),
                    href: match[1].replace(/\$\{[^}]*\}/g, DYNAMIC_SEGMENT),
                });
            }
        }
    }
    return hits;
}

/**
 * Whether a scanned href can be served by some shipped route. A wildcard segment (a `${…}` hole in
 * the source) matches any pattern segment, so dynamic route parts never false-fail; every literal
 * segment must match exactly, so a hard-coded unshipped path always fails.
 */
function scannedHrefIsShipped(href: string): boolean {
    const segments = href.split("#")[0].split("?")[0].split("/").filter(Boolean);
    return shippedRoutes.some((route) => {
        const pattern = route.split("/").filter(Boolean);
        if (pattern.length !== segments.length) return false;
        return pattern.every((patternSegment, index) => {
            const segment = segments[index];
            if (segment.includes(DYNAMIC_SEGMENT)) return true;
            if (patternSegment.startsWith("[")) return segment.length > 0;
            return patternSegment === segment;
        });
    });
}

describe("every literal in-app href targets a shipped route", () => {
    it("finds no app-shell href pointing at an unshipped route", () => {
        const violations = literalAppHrefs().filter(({ href }) => {
            const [first] = href.split("#")[0].split("?")[0].split("/").filter(Boolean);
            if (!first || first.includes(DYNAMIC_SEGMENT)) return false;
            if (!APP_SHELL_TOP_SEGMENTS.has(first)) return false;
            return !scannedHrefIsShipped(href);
        });

        expect(violations).toEqual([]);
    });

    it("scans a corpus large enough to be meaningful", () => {
        expect(literalAppHrefs().length).toBeGreaterThan(50);
    });
});
