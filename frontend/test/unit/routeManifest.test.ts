import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    matchesShippedRoute,
    resolveShippedRoute,
    SHIPPED_APP_ROUTES,
} from "@/app/lib/routeManifest";
import {
    ACTIVITY_URL_KEY,
    COMMENT_URL_KEY,
    DEEP_LINK_URL_KEYS,
    NOTE_URL_KEY,
    TASK_URL_KEY,
} from "@/app/hooks/listStateUrl";
import {
    companyDealsHref,
    DEAL_COMPANY_FILTER_KEY,
    DEAL_PIPELINE_FILTER_KEY,
    pipelineDealsHref,
} from "@/app/components/records/deals/dealLinks";
import { buildEvents, type CalendarEvent, type CalendarEventKind } from "@/app/lib/calendar";
import type { Activity, Deal, Note, Task } from "@/app/lib/types";

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
    params: Record<string, string>;
    routes: string[];
};

function readSharedManifest(): SharedManifest {
    const parsed: unknown = JSON.parse(readFileSync(SHARED_MANIFEST_PATH, "utf8"));
    if (typeof parsed !== "object" || parsed === null) throw new Error("shared route manifest is not an object");
    const { params, routes } = parsed as { params?: unknown; routes?: unknown };
    if (typeof params !== "object" || params === null) throw new Error("shared route manifest has no params");
    if (!Array.isArray(routes)) throw new Error("shared route manifest has no routes");
    return {
        params: Object.fromEntries(
            Object.entries(params as Record<string, unknown>).map(([key, value]) => [key, String(value)]),
        ),
        routes: routes.map((route) => String(route)),
    };
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
        expect(sharedManifest.params).toEqual({ ...DEEP_LINK_URL_KEYS });
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
        const href = companyDealsHref(7);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_COMPANY_FILTER_KEY)).toBe("7");
    });

    it("drops the company filter for a contact with no company rather than filtering on nothing", () => {
        expect(companyDealsHref(null)).toBe("/records/deals");
        expect(companyDealsHref(undefined)).toBe("/records/deals");
    });

    it("links a pipeline's deal count to the deals browser filtered by pipeline", () => {
        const href = pipelineDealsHref(3);

        expect(resolveShippedRoute(href)).toBe("/records/deals");
        expect(queryOf(href).get(DEAL_PIPELINE_FILTER_KEY)).toBe("3");
    });

    it("resolves every record-scoped notification deep link the backend emits", () => {
        const links = [
            `/records/contacts/42?${TASK_URL_KEY}=9`,
            `/records/companies/42?${ACTIVITY_URL_KEY}=9`,
            `/records/deals/42?${COMMENT_URL_KEY}=9`,
            `/activity/notes/42?${NOTE_URL_KEY}=9`,
        ];

        for (const href of links) {
            expect(matchesShippedRoute(href)).toBe(true);
            for (const key of queryOf(href).keys()) {
                expect(Object.values(DEEP_LINK_URL_KEYS)).toContain(key);
            }
        }
    });
});
