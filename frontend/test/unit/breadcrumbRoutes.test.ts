import { readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    buildBreadcrumbNodes,
    resolveBreadcrumbRoute,
    type BreadcrumbCrumb,
    type BreadcrumbMessageKey,
    type BreadcrumbRouteContext,
} from "@/app/lib/breadcrumbRoutes";
import { DEFAULT_CAPABILITIES } from "@/app/lib/api";
import { NO_NAV_ACCESS, resolveNavAccess, type NavAccess } from "@/app/lib/navAccess";

const ALL_ACCESS: NavAccess = {
    goals: true,
    auditLog: true,
    captureReviews: true,
    campaigns: true,
    workflows: true,
    diagnostics: true,
};

function context(overrides: Partial<BreadcrumbRouteContext> = {}): BreadcrumbRouteContext {
    return {
        workspaceName: "Northstar",
        organizationName: "Aperture",
        organizationAccessible: true,
        navAccess: ALL_ACCESS,
        dynamicLabels: new Map(),
        translate: (key: BreadcrumbMessageKey) => key,
        ...overrides,
    };
}

const ROUTE_CASES = [
    ["/account", "redirect"],
    ["/account/profile", "shell"],
    ["/account/security", "shell"],
    ["/account/connections", "shell"],
    ["/account/connections/reviews", "shell"],
    ["/account/notifications", "shell"],
    ["/account/invites", "shell"],
    ["/activity/activities/1", "shell"],
    ["/activity/all", "shell"],
    ["/activity/notes/1", "shell"],
    ["/activity/notes/new", "owned"],
    ["/activity/notes", "shell"],
    ["/activity/tasks/1", "shell"],
    ["/activity/tasks", "shell"],
    ["/admin/logs", "shell"],
    ["/dashboard", "shell"],
    ["/library/documents/1", "owned"],
    ["/library/documents/new", "owned"],
    ["/library/documents", "shell"],
    ["/library/files", "shell"],
    ["/library/tags", "shell"],
    ["/marketing/campaigns/1", "shell"],
    ["/marketing/campaigns", "shell"],
    ["/me", "shell"],
    ["/notifications", "shell"],
    ["/organization", "redirect"],
    ["/organization/ai", "shell"],
    ["/organization/allowed-domains", "shell"],
    ["/organization/audit", "shell"],
    ["/organization/data-requests", "shell"],
    ["/organization/diagnostics", "shell"],
    ["/organization/members", "shell"],
    ["/organization/overview", "shell"],
    ["/organization/sso", "shell"],
    ["/overview/analytics", "shell"],
    ["/overview/calendar", "shell"],
    ["/overview/introductions", "shell"],
    ["/overview/map", "shell"],
    ["/overview/reports/1/edit", "shell"],
    ["/overview/reports/1", "shell"],
    ["/overview/reports/1/snapshots/2", "shell"],
    ["/overview/reports/1/snapshots", "redirect"],
    ["/overview/reports/goals", "shell"],
    ["/overview/reports/new", "shell"],
    ["/overview/reports", "shell"],
    ["/radar", "shell"],
    ["/records/approval-policies", "shell"],
    ["/records/companies/1", "shell"],
    ["/records/companies", "shell"],
    ["/records/contacts/1", "shell"],
    ["/records/contacts", "shell"],
    ["/records/deals/1/documents/2/print", "owned"],
    ["/records/deals/1", "shell"],
    ["/records/deals", "shell"],
    ["/records/pipelines", "shell"],
    ["/records/products", "shell"],
    ["/search", "shell"],
    ["/settings/custom-fields", "shell"],
    ["/settings/data", "shell"],
    ["/settings/delivery", "shell"],
    ["/settings/diagnostics", "shell"],
    ["/settings/email", "shell"],
    ["/settings/general", "shell"],
    ["/settings/members", "shell"],
    ["/settings/membership", "redirect"],
    ["/settings/notifications", "redirect"],
    ["/settings", "redirect"],
    ["/settings/roles", "shell"],
    ["/settings/rules", "redirect"],
    ["/settings/security", "redirect"],
    ["/settings/sso", "redirect"],
    ["/settings/workflows/1", "redirect"],
    ["/users/1", "shell"],
    ["/users", "shell"],
    ["/workflows/1", "owned"],
    ["/workflows/1/runs/canonical-1", "shell"],
    ["/workflows/new", "owned"],
    ["/workflows/operations", "shell"],
    ["/workflows", "shell"],
    ["/workflows/recipes/deal-won-handoff", "shell"],
    ["/workflows/recipes", "shell"],
] as const;

const DYNAMIC_SEGMENT_EXAMPLES: Readonly<Record<string, string>> = {
    id: "1",
    docId: "2",
    legacyRuleId: "1",
    recipeKey: "deal-won-handoff",
    runKey: "canonical-1",
    snapshotId: "2",
    workflowId: "1",
};

function authenticatedPagePaths(directory: string, segments: string[] = []): string[] {
    const paths: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isFile() && entry.name === "page.tsx") {
            paths.push(`/${segments.join("/")}`);
            continue;
        }
        if (!entry.isDirectory()) continue;
        const dynamicName = entry.name.startsWith("[") && entry.name.endsWith("]")
            ? entry.name.slice(1, -1)
            : null;
        const segment = dynamicName ? DYNAMIC_SEGMENT_EXAMPLES[dynamicName] : entry.name;
        if (!segment) throw new Error(`No breadcrumb sample for dynamic segment ${entry.name}`);
        paths.push(...authenticatedPagePaths(path.join(directory, entry.name), [...segments, segment]));
    }
    return paths;
}

describe("breadcrumb route registry", () => {
    it("classifies every authenticated App Router page", () => {
        const registered = new Set<string>(ROUTE_CASES.map(([pathname]) => pathname));
        const pages = authenticatedPagePaths(path.join(process.cwd(), "app", "(app)"));

        expect(pages.filter((pathname) => !registered.has(pathname))).toEqual([]);
    });

    it.each(ROUTE_CASES)("classifies %s as %s", (pathname, kind) => {
        expect(resolveBreadcrumbRoute(pathname, context()).kind).toBe(kind);
    });

    it("resolves a fresh record deep link without exposing its id", () => {
        const resolution = resolveBreadcrumbRoute("/records/contacts/42", context());

        expect(resolution.crumbs.map((crumb) => crumb.label)).toEqual(["Northstar", "contacts"]);
        expect(resolution.crumbs.some((crumb) => crumb.label.includes("42"))).toBe(false);
        expect(resolution.crumbs.at(-1)).toMatchObject({
            pathname: "/records/contacts",
            current: false,
            returnCollection: "contacts",
        });
    });

    it("uses only a registered entity label for a dynamic record", () => {
        const dynamicLabels = new Map([["/records/contacts/42", "Ada Lovelace"]]);
        const resolution = resolveBreadcrumbRoute("/records/contacts/42", context({ dynamicLabels }));

        expect(resolution.crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "contacts",
            "Ada Lovelace",
        ]);
        expect(resolution.crumbs.at(-1)?.current).toBe(true);
    });

    it("reads live workspace and organization identities on every resolution", () => {
        const before = resolveBreadcrumbRoute("/settings/members", context()).crumbs;
        const after = resolveBreadcrumbRoute("/settings/members", context({ workspaceName: "Voyager" })).crumbs;
        const organization = resolveBreadcrumbRoute(
            "/organization/members",
            context({ organizationName: "Black Mesa" }),
        ).crumbs;

        expect(before[0].label).toBe("Northstar");
        expect(after[0].label).toBe("Voyager");
        expect(organization[0].label).toBe("Black Mesa");
    });

    it("builds report edit and snapshot hierarchies without fabricating a report name", () => {
        const labels = new Map([["/overview/reports/7", "Pipeline Health"]]);
        expect(resolveBreadcrumbRoute(
            "/overview/reports/7/edit",
            context({ dynamicLabels: labels }),
        ).crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "reports",
            "Pipeline Health",
            "edit",
        ]);
        expect(resolveBreadcrumbRoute(
            "/overview/reports/7/snapshots/9",
            context(),
        ).crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "reports",
            "snapshot",
        ]);
    });

    it("builds workflow and recipe hierarchies from registered labels", () => {
        const labels = new Map([
            ["/workflows/5", "Renewal follow-up"],
            ["/workflows/recipes/deal-won-handoff", "Deal won handoff"],
        ]);
        expect(resolveBreadcrumbRoute(
            "/workflows/5/runs/canonical-10",
            context({ dynamicLabels: labels }),
        ).crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "workflows",
            "Renewal follow-up",
            "run",
        ]);
        expect(resolveBreadcrumbRoute(
            "/workflows/recipes/deal-won-handoff",
            context({ dynamicLabels: labels }),
        ).crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "workflows",
            "recipes",
            "Deal won handoff",
        ]);
    });

    it.each([
        ["/overview/reports/goals", "goals"],
        ["/admin/logs", "auditLog"],
        ["/account/connections/reviews", "captureReviews"],
        ["/marketing/campaigns", "campaigns"],
        ["/workflows", "workflows"],
        ["/settings/diagnostics", "diagnostics"],
    ] as const)("fails closed for inaccessible route %s", (pathname, access) => {
        expect(resolveBreadcrumbRoute(pathname, context({
            navAccess: { ...ALL_ACCESS, [access]: false },
        }))).toEqual({ kind: "denied", crumbs: [] });
    });

    it("does not link organization routes for a non-administrator", () => {
        expect(resolveBreadcrumbRoute(
            "/organization/members",
            context({ organizationAccessible: false }),
        )).toEqual({ kind: "denied", crumbs: [] });
    });

    it.each([
        "/workflows",
        "/workflows/new",
        "/workflows/5",
        "/workflows/5/runs/canonical-10",
        "/workflows/operations",
        "/workflows/recipes",
        "/workflows/recipes/deal-won-handoff",
    ])("does not expose workflow ancestors without rule-management access for %s", (pathname) => {
        expect(resolveBreadcrumbRoute(pathname, context({
            navAccess: { ...ALL_ACCESS, workflows: false },
        }))).toEqual({ kind: "denied", crumbs: [] });
    });

    it("fails closed when all gated navigation state is unavailable", () => {
        expect(resolveBreadcrumbRoute(
            "/admin/logs",
            context({ navAccess: NO_NAV_ACCESS }),
        )).toEqual({ kind: "denied", crumbs: [] });
    });

    it.each([
        "/records/contacts-ish",
        "/records/contacts/42/more",
        "/overview/reports/not-an-id",
        "/workflows/5/runs",
        "/workflows/5/runs/not-a-run",
        "/workflows/recipes/not-a-recipe",
        "/settings/general/extra",
    ])("does not prefix-match unknown route %s", (pathname) => {
        expect(resolveBreadcrumbRoute(pathname, context())).toEqual({ kind: "unknown", crumbs: [] });
    });
});

describe("workflow navigation access", () => {
    it("follows the backend rule-management permission and fails closed", () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ["RULE_MANAGE"]).workflows).toBe(true);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ["PERSON_READ"]).workflows).toBe(false);
        expect(NO_NAV_ACCESS.workflows).toBe(false);
    });
});

describe("breadcrumb collapse", () => {
    const crumbs: BreadcrumbCrumb[] = [
        { pathname: "/dashboard", label: "Northstar", current: false },
        { pathname: "/workflows", label: "Workflows", current: false },
        { pathname: "/workflows/recipes", label: "Recipes", current: false },
        { pathname: "/workflows/recipes/example", label: "Example", current: false },
        { pathname: "/workflows/recipes/example/run", label: "Run", current: true },
    ];

    it("keeps the root, overflow, parent, and current page on desktop", () => {
        const nodes = buildBreadcrumbNodes(crumbs, "desktop");
        expect(nodes.map((node) => node.kind === "crumb" ? node.crumb.label : "overflow")).toEqual([
            "Northstar",
            "overflow",
            "Example",
            "Run",
        ]);
        expect(nodes[1]).toMatchObject({
            kind: "ellipsis",
            hidden: [{ label: "Workflows" }, { label: "Recipes" }],
        });
    });

    it("keeps only overflow, parent, and current page on mobile", () => {
        const nodes = buildBreadcrumbNodes(crumbs, "mobile");
        expect(nodes.map((node) => node.kind === "crumb" ? node.crumb.label : "overflow")).toEqual([
            "overflow",
            "Example",
            "Run",
        ]);
        expect(nodes[0]).toMatchObject({
            kind: "ellipsis",
            hidden: [{ label: "Northstar" }, { label: "Workflows" }, { label: "Recipes" }],
        });
    });

    it("does not collapse a short trail", () => {
        expect(buildBreadcrumbNodes(crumbs.slice(0, 2), "mobile")).toEqual([
            { kind: "crumb", crumb: crumbs[0] },
            { kind: "crumb", crumb: crumbs[1] },
        ]);
    });
});
