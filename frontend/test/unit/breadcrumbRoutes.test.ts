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
import { settingsRouteServed } from "@/app/lib/settingsEntryPoints";
import { SETTINGS_GROUPS, type SettingsGroup } from "@/app/lib/settingsManifest";
import { NO_NAV_ACCESS, resolveNavAccess, type NavAccess } from "@/app/lib/navAccess";

const ALL_ACCESS: NavAccess = {
    goals: true,
    auditLog: true,
    captureReviews: "enabled",
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
        translateMessage: (key: string) => key,
        ...overrides,
    };
}

const ROUTE_CASES = [
    ["/account", "redirect"],
    ["/account/profile", "redirect"],
    ["/account/security", "redirect"],
    ["/account/connections", "redirect"],
    ["/account/connections/reviews", "shell"],
    ["/account/notifications", "redirect"],
    ["/account/invites", "redirect"],
    ["/activity/activities/1", "shell"],
    ["/activity/all", "shell"],
    ["/activity/notes/1", "shell"],
    ["/activity/notes/new", "owned"],
    ["/activity/notes", "shell"],
    ["/activity/tasks/1", "shell"],
    ["/activity/tasks", "shell"],
    ["/admin/logs", "redirect"],
    ["/ask-connex", "owned"],
    ["/ask-connex/1", "owned"],
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
    ["/organization/ai", "redirect"],
    ["/organization/allowed-domains", "redirect"],
    ["/organization/audit", "redirect"],
    ["/organization/data-requests", "redirect"],
    ["/organization/diagnostics", "redirect"],
    ["/organization/members", "redirect"],
    ["/organization/overview", "redirect"],
    ["/organization/sso", "redirect"],
    ["/activity/calendar", "shell"],
    ["/insights/analytics", "shell"],
    ["/insights/reports/1/edit", "shell"],
    ["/insights/reports/1", "shell"],
    ["/insights/reports/1/snapshots/2", "shell"],
    ["/insights/reports/1/snapshots", "redirect"],
    ["/insights/reports/goals", "shell"],
    ["/insights/reports/new", "shell"],
    ["/insights/reports", "shell"],
    ["/intelligence/introductions", "shell"],
    ["/intelligence/map", "shell"],
    ["/intelligence/radar", "shell"],
    ["/overview/analytics", "redirect"],
    ["/overview/calendar", "redirect"],
    ["/overview/introductions", "redirect"],
    ["/overview/map", "redirect"],
    ["/overview/reports/1/edit", "redirect"],
    ["/overview/reports/1", "redirect"],
    ["/overview/reports/1/snapshots/2", "redirect"],
    ["/overview/reports/1/snapshots", "redirect"],
    ["/overview/reports/goals", "redirect"],
    ["/overview/reports/new", "redirect"],
    ["/overview/reports", "redirect"],
    ["/radar", "redirect"],
    ["/records/approval-policies", "redirect"],
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
    ["/settings/custom-fields", "redirect"],
    ["/settings/data", "redirect"],
    ["/settings/delivery", "redirect"],
    ["/settings/diagnostics", "redirect"],
    ["/settings/email", "redirect"],
    ["/settings/general", "redirect"],
    ["/settings/members", "redirect"],
    ["/settings/membership", "redirect"],
    ["/settings/notifications", "redirect"],
    ["/settings", "shell"],
    ["/settings/qualification", "redirect"],
    ["/settings/roles", "redirect"],
    ["/settings/rules", "redirect"],
    ["/settings/security", "redirect"],
    ["/settings/sso", "redirect"],
    ["/settings/workflows/1", "redirect"],
    ["/settings/workspace/people", "shell"],
    ["/settings/workspace/communications", "shell"],
    ["/settings/workspace/data-privacy", "shell"],
    ["/settings/workspace/general", "shell"],
    ["/settings/workspace/crm", "shell"],
    ["/settings/workspace/audit-diagnostics", "shell"],
    ["/settings/organization/general", "shell"],
    ["/settings/organization/identity", "shell"],
    ["/settings/personal/connected-accounts", "shell"],
    ["/settings/personal/notifications", "shell"],
    ["/settings/personal/profile", "shell"],
    ["/settings/personal/security", "shell"],
    ["/settings/personal/workspaces", "shell"],
    ["/settings/organization/ai-governance", "shell"],
    ["/settings/organization/data-requests", "shell"],
    ["/settings/organization/audit-diagnostics", "shell"],
    ["/users/1", "shell"],
    ["/users", "redirect"],
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
    sessionId: "1",
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
        const before = resolveBreadcrumbRoute("/settings/workspace/general", context()).crumbs;
        const after = resolveBreadcrumbRoute(
            "/settings/workspace/general",
            context({ workspaceName: "Voyager" }),
        ).crumbs;
        const organization = resolveBreadcrumbRoute(
            "/settings/organization/identity",
            context({ organizationName: "Black Mesa" }),
        ).crumbs;

        expect(before[0].label).toBe("Northstar");
        expect(after[0].label).toBe("Voyager");
        expect(organization[0].label).toBe("Black Mesa");
    });

    it("builds report edit and snapshot hierarchies without fabricating a report name", () => {
        const labels = new Map([["/insights/reports/7", "Pipeline Health"]]);
        expect(resolveBreadcrumbRoute(
            "/insights/reports/7/edit",
            context({ dynamicLabels: labels }),
        ).crumbs.map((crumb) => crumb.label)).toEqual([
            "Northstar",
            "reports",
            "Pipeline Health",
            "edit",
        ]);
        expect(resolveBreadcrumbRoute(
            "/insights/reports/7/snapshots/9",
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
        ["/insights/reports/goals", "goals"],
        ["/marketing/campaigns", "campaigns"],
        ["/workflows", "workflows"],
    ] as const)("fails closed for inaccessible route %s", (pathname, access) => {
        const navAccess: NavAccess = { ...ALL_ACCESS, [access]: false };
        expect(resolveBreadcrumbRoute(pathname, context({
            navAccess,
        }))).toEqual({ kind: "denied", crumbs: [] });
    });

    /**
     * The two audit and diagnostics addresses that used to be checked above are now forwards, and a
     * forward is classified as one before access is consulted. That is not a loosening: what the
     * gate protects is that an inaccessible route discloses no trail, and both classifications
     * yield none. The reader never sees either, because the address redirects before it renders —
     * and the destination it lands on refuses in place, which is where the refusal now belongs.
     */
    it.each(["/admin/logs", "/settings/diagnostics"])(
        "discloses no trail for retired address %s, whatever the viewer may reach",
        (pathname) => {
            const navAccess: NavAccess = { ...ALL_ACCESS, auditLog: false, diagnostics: false };
            const resolution = resolveBreadcrumbRoute(pathname, context({ navAccess }));

            expect(resolution.crumbs).toEqual([]);
            expect(resolution.kind).toBe("redirect");
        },
    );

    it.each([
        ["enabled", "shell"],
        ["disabled", "denied"],
        ["unavailable", "shell"],
    ] as const)("resolves capture-review breadcrumbs with %s availability as %s", (availability, kind) => {
        expect(resolveBreadcrumbRoute(
            "/account/connections/reviews",
            context({ navAccess: { ...ALL_ACCESS, captureReviews: availability } }),
        ).kind).toBe(kind);
    });

    it("does not link the retired organization routes for a non-administrator", () => {
        const resolution = resolveBreadcrumbRoute(
            "/organization/members",
            context({ organizationAccessible: false }),
        );

        expect(resolution.crumbs, "a retired address discloses no trail either way").toEqual([]);
        expect(resolution.kind).toBe("redirect");
    });

    it.each([
        "/settings/organization/general",
        "/settings/organization/identity",
        "/settings/organization/ai-governance",
        "/settings/organization/data-requests",
        "/settings/organization/audit-diagnostics",
    ])("does not link %s for a non-administrator either", (pathname) => {
        expect(
            resolveBreadcrumbRoute(pathname, context({ organizationAccessible: false })),
            "moving an organization job under /settings must not move it out from behind the organization gate",
        ).toEqual({ kind: "denied", crumbs: [] });
    });

    it("roots an organization settings trail at the organization, not the active workspace", () => {
        const trail = resolveBreadcrumbRoute("/settings/organization/identity", context());

        expect(trail.crumbs.map((crumb) => crumb.pathname)).toEqual([
            "/organization",
            "/settings",
            "/settings/organization/identity",
        ]);
    });

    it.each(
        (SETTINGS_GROUPS as readonly SettingsGroup[]).filter((group) =>
            settingsRouteServed(group.route),
        ),
    )(
        "names the canonical destination $route with the manifest's own key for its group",
        (group) => {
            const trail = resolveBreadcrumbRoute(group.route, context());
            const current = trail.crumbs.at(-1);

            expect(trail.kind).toBe("shell");
            expect(current?.pathname).toBe(group.route);
            expect(
                current?.label,
                "a canonical destination's crumb is the group's manifest label, not a second name kept beside it",
            ).toBe(group.titleKey);
        },
    );

    it("roots a workspace settings trail at the workspace and passes through Settings", () => {
        const trail = resolveBreadcrumbRoute("/settings/workspace/people", context());

        expect(trail.crumbs.map((crumb) => crumb.pathname)).toEqual([
            "/dashboard",
            "/settings",
            "/settings/workspace/people",
        ]);
    });

    /**
     * Every scope group is served now, so this can no longer be shown against the manifest: the
     * filter it used to iterate is empty, and an assertion over an empty list passes by saying
     * nothing. The rule is the same one — a canonical route no page serves gets no trail, because
     * `CANONICAL_SETTINGS_GROUPS` only indexes the routes `settingsRouteServed` confirms — so it is
     * exercised here against an address shaped exactly like a scope-group route that nothing serves.
     *
     * That the manifest currently has no such group is itself worth asserting, and is the first
     * expectation below: it is the finished state this epic was working towards, and a group added
     * without a page would take it back.
     */
    it("leaves a canonical route no page serves without a trail", () => {
        const unserved = (SETTINGS_GROUPS as readonly SettingsGroup[]).filter(
            (group) => !settingsRouteServed(group.route),
        );

        expect(
            unserved.map((group) => group.id),
            "every scope group #1340 names now has a page behind it",
        ).toEqual([]);
        expect(settingsRouteServed("/settings/personal/unbuilt")).toBe(false);
        expect(resolveBreadcrumbRoute("/settings/personal/unbuilt", context()).kind).toBe("unknown");
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
        expect(
            resolveBreadcrumbRoute(
                "/marketing/campaigns",
                context({ navAccess: NO_NAV_ACCESS }),
            ),
            "the probe moved off /admin/logs, which is a forward now and answers as one before access is consulted. It has to be a route that still renders and is still gated, which campaigns is; the audit job's own destination is deliberately ungated here, because it absorbed two separately gated jobs and refuses each in place.",
        ).toEqual({ kind: "denied", crumbs: [] });
    });

    it.each([
        "/records/contacts-ish",
        "/records/contacts/42/more",
        "/insights/reports/not-an-id",
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
