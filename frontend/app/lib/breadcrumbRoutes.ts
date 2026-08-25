import { CONNECTED_ACCOUNTS_ROUTE } from "@/app/lib/connectedAccountsSections";
import type { NavAccess } from "@/app/lib/navAccess";
import type { RecordCollection } from "@/app/lib/recordReturnPath";
import { settingsRouteServed } from "@/app/lib/settingsEntryPoints";
import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    SETTINGS_HOME_ROUTE,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";
import { isWorkflowRecipeKey } from "@/app/lib/workflowOperations";

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

export type BreadcrumbMessageKey =
    | "activities"
    | "aiProvider"
    | "allowedDomains"
    | "analytics"
    | "approvalPolicies"
    | "auditLog"
    | "calendar"
    | "campaigns"
    | "captureReviews"
    | "companies"
    | "contacts"
    | "customFields"
    | "dashboard"
    | "dataRequests"
    | "deals"
    | "delivery"
    | "diagnostics"
    | "documents"
    | "edit"
    | "email"
    | "files"
    | "goals"
    | "introductions"
    | "map"
    | "members"
    | "newReport"
    | "notes"
    | "notifications"
    | "operations"
    | "organization"
    | "overview"
    | "peopleAccess"
    | "communications"
    | "crmConfiguration"
    | "auditDiagnostics"
    | "identityAdministrators"
    | "aiDataGovernance"
    | "pipelines"
    | "products"
    | "myWork"
    | "qualification"
    | "radar"
    | "recipes"
    | "reports"
    | "roles"
    | "run"
    | "search"
    | "settings"
    | "singleSignOn"
    | "snapshot"
    | "tags"
    | "tasks"
    | "users"
    | "workspace"
    | "workflows";

export type BreadcrumbCrumb = {
    pathname: string;
    label: string;
    current: boolean;
    returnCollection?: RecordCollection;
};

export type BreadcrumbResolution = {
    kind: "shell" | "owned" | "redirect" | "denied" | "unknown";
    crumbs: BreadcrumbCrumb[];
};

export type BreadcrumbRouteContext = {
    workspaceName: string | null;
    organizationName: string | null;
    organizationAccessible: boolean;
    navAccess: NavAccess;
    dynamicLabels: ReadonlyMap<string, string>;
    translate: (key: BreadcrumbMessageKey) => string;
    /**
     * Resolves an absolute message key, namespace included.
     *
     * The canonical settings destinations are named by the committed settings manifest rather than
     * by this registry's own closed key union, so their crumbs are translated through here. Every
     * other route keeps {@link translate}.
     */
    translateMessage: (key: string) => string;
};

export type BreadcrumbDisplayMode = "desktop" | "mobile";

export type BreadcrumbNode =
    | { kind: "crumb"; crumb: BreadcrumbCrumb }
    | { kind: "ellipsis"; hidden: BreadcrumbCrumb[] };

type StaticWorkspaceRoute = {
    key: BreadcrumbMessageKey;
    access?: keyof NavAccess;
};

const STATIC_WORKSPACE_ROUTES: Readonly<Record<string, StaticWorkspaceRoute>> = {
    "/dashboard": { key: "dashboard" },
    "/radar": { key: "radar" },
    "/overview/calendar": { key: "calendar" },
    "/overview/map": { key: "map" },
    "/overview/introductions": { key: "introductions" },
    "/overview/analytics": { key: "analytics" },
    "/overview/reports": { key: "reports" },
    "/records/companies": { key: "companies" },
    "/records/contacts": { key: "contacts" },
    "/records/deals": { key: "deals" },
    "/records/pipelines": { key: "pipelines" },
    "/records/products": { key: "products" },
    "/records/approval-policies": { key: "approvalPolicies" },
    "/marketing/campaigns": { key: "campaigns", access: "campaigns" },
    "/activity/all": { key: "activities" },
    "/activity/tasks": { key: "tasks" },
    "/activity/notes": { key: "notes" },
    "/library/documents": { key: "documents" },
    "/library/tags": { key: "tags" },
    "/library/files": { key: "files" },
    "/notifications": { key: "notifications" },
    "/search": { key: "search" },
    "/me": { key: "myWork" },
    "/users": { key: "users" },
    "/workflows": { key: "workflows", access: "workflows" },
    "/admin/logs": { key: "auditLog", access: "auditLog" },
};

const SETTINGS_ROUTES: Readonly<Record<string, StaticWorkspaceRoute>> = {
    "/settings/members": { key: "members" },
    "/settings/roles": { key: "roles" },
    "/settings/custom-fields": { key: "customFields" },
    "/settings/qualification": { key: "qualification" },
    "/settings/email": { key: "email" },
    "/settings/delivery": { key: "delivery" },
    "/settings/diagnostics": { key: "diagnostics", access: "diagnostics" },
};

/**
 * The canonical settings destinations, indexed by the route each scope group owns (#1340 PR 7).
 *
 * Their trails are derived rather than tabulated: the group is the unit of canonical ownership, so
 * the crumb a reader lands on is the group's own name from the manifest, and its scope decides
 * whether the trail is rooted in the workspace or in the organization. A group whose route no page
 * serves is skipped, which leaves the legacy tables below to answer for the addresses that still
 * do — the same served-or-not fact the settings navigation and the entry points already read.
 *
 * The legacy `/settings/*` and `/organization/*` rows keep their shipped trails until their
 * redirects land; nothing about them moves here.
 */
const CANONICAL_SETTINGS_GROUPS: ReadonlyMap<string, SettingsGroup> = new Map(
    MANIFEST_GROUPS
        .filter((group) => group.titleKey !== null && settingsRouteServed(group.route))
        .map((group) => [group.route, group]),
);

const ORGANIZATION_ROUTES: Readonly<Record<string, BreadcrumbMessageKey>> = {
    "/organization/overview": "overview",
    "/organization/members": "members",
    "/organization/allowed-domains": "allowedDomains",
    "/organization/sso": "singleSignOn",
    "/organization/ai": "aiProvider",
    "/organization/audit": "auditLog",
    "/organization/data-requests": "dataRequests",
    "/organization/diagnostics": "diagnostics",
};

/** Literal authenticated routes referenced by the breadcrumb registry. */
export const BREADCRUMB_STATIC_ROUTE_PATHS = [...new Set([
    ...Object.keys(STATIC_WORKSPACE_ROUTES),
    ...Object.keys(SETTINGS_ROUTES),
    ...CANONICAL_SETTINGS_GROUPS.keys(),
    ...Object.keys(ORGANIZATION_ROUTES),
    "/account/connections/reviews",
    "/overview/reports/goals",
    "/overview/reports/new",
    "/workflows/operations",
    "/workflows/recipes",
])].sort();

/**
 * The one resolver redirect that can also render.
 *
 * `/account/connections/reviews` forwards in every ordinary case, but when the deployment has no
 * capture capability at all it stops and explains that in place instead. A page that can render
 * needs a trail, so this address stays classified as a shell rather than following the manifest
 * into {@link REDIRECT_ROUTES}.
 */
const RENDERING_RESOLVER_REDIRECTS = new Set(["/account/connections/reviews"]);

/**
 * The addresses that forward instead of rendering, read from the settings manifest.
 *
 * Hand-listing these was workable while there were seven; #1340 PR 8 retired twenty more, and a
 * list maintained beside the manifest would drift the first time a destination moved — leaving a
 * redirecting address quietly claiming a breadcrumb trail nobody would ever see. The manifest
 * already records which addresses forward, so this reads that rather than restating it.
 *
 * Parameterized routes are excluded because they are patterns rather than addresses;
 * `/settings/workflows/[legacyRuleId]` is matched by {@link LEGACY_WORKFLOW_REDIRECT} instead.
 */
const REDIRECT_ROUTES = new Set<string>(
    SETTINGS_ENTRIES.filter(
        (entry) =>
            entry.redirectsTo !== null
            && !entry.currentRoute.includes("[")
            && !RENDERING_RESOLVER_REDIRECTS.has(entry.currentRoute),
    ).map((entry) => entry.currentRoute),
);

const OWNED_ROUTE_PATTERNS = [
    /^\/activity\/notes\/new$/,
    /^\/ask-connex(?:\/[1-9]\d*)?$/,
    /^\/library\/documents\/(?:new|[1-9]\d*)$/,
    /^\/records\/deals\/[1-9]\d*\/documents\/[1-9]\d*\/print$/,
    /^\/workflows\/(?:new|[1-9]\d*)$/,
] as const;

const LEGACY_WORKFLOW_REDIRECT = /^\/settings\/workflows\/[1-9]\d*$/;
const REPORT_SNAPSHOTS_REDIRECT = /^\/overview\/reports\/[1-9]\d*\/snapshots$/;
const DYNAMIC_RECORD_ROUTE = /^\/(records\/(contacts|companies|deals)|activity\/(tasks|notes)|activity\/activities)\/([1-9]\d*)$/;
const USER_ROUTE = /^\/users\/([1-9]\d*)$/;
const CAMPAIGN_ROUTE = /^\/marketing\/campaigns\/([1-9]\d*)$/;
const REPORT_ROUTE = /^\/overview\/reports\/([1-9]\d*)$/;
const REPORT_EDIT_ROUTE = /^\/overview\/reports\/([1-9]\d*)\/edit$/;
const REPORT_SNAPSHOT_ROUTE = /^\/overview\/reports\/([1-9]\d*)\/snapshots\/([1-9]\d*)$/;
const WORKFLOW_RUN_ROUTE = /^\/workflows\/([1-9]\d*)\/runs\/(?:canonical|legacy)-[1-9]\d*$/;
const WORKFLOW_RECIPE_ROUTE = /^\/workflows\/recipes\/([^/]+)$/;

const RECORD_ROUTE_METADATA: Readonly<Record<string, {
    collectionPath: string;
    collectionKey: BreadcrumbMessageKey;
    returnCollection: RecordCollection;
}>> = {
    "records/contacts": {
        collectionPath: "/records/contacts",
        collectionKey: "contacts",
        returnCollection: "contacts",
    },
    "records/companies": {
        collectionPath: "/records/companies",
        collectionKey: "companies",
        returnCollection: "companies",
    },
    "records/deals": {
        collectionPath: "/records/deals",
        collectionKey: "deals",
        returnCollection: "deals",
    },
    "activity/tasks": {
        collectionPath: "/activity/tasks",
        collectionKey: "tasks",
        returnCollection: "tasks",
    },
    "activity/notes": {
        collectionPath: "/activity/notes",
        collectionKey: "notes",
        returnCollection: "notes",
    },
    "activity/activities": {
        collectionPath: "/activity/all",
        collectionKey: "activities",
        returnCollection: "activities",
    },
};

function translatedCrumb(
    pathname: string,
    key: BreadcrumbMessageKey,
    context: BreadcrumbRouteContext,
    current = false,
    returnCollection?: RecordCollection,
): BreadcrumbCrumb {
    return {
        pathname,
        label: context.translate(key),
        current,
        ...(returnCollection ? { returnCollection } : {}),
    };
}

function literalCrumb(pathname: string, label: string, current = false): BreadcrumbCrumb {
    return { pathname, label, current };
}

function cleanLabel(label: string | null | undefined): string | null {
    const cleaned = label?.trim();
    return cleaned ? cleaned : null;
}

function workspaceRoot(context: BreadcrumbRouteContext): BreadcrumbCrumb | null {
    const label = cleanLabel(context.workspaceName);
    return label ? literalCrumb("/dashboard", label) : null;
}

function organizationRoot(context: BreadcrumbRouteContext): BreadcrumbCrumb {
    return literalCrumb(
        "/organization",
        cleanLabel(context.organizationName) ?? context.translate("organization"),
    );
}

function withWorkspace(
    context: BreadcrumbRouteContext,
    crumbs: BreadcrumbCrumb[],
): BreadcrumbCrumb[] {
    const root = workspaceRoot(context);
    return root ? [root, ...crumbs] : crumbs;
}

function dynamicCrumb(
    pathname: string,
    context: BreadcrumbRouteContext,
    current: boolean,
): BreadcrumbCrumb | null {
    const label = cleanLabel(context.dynamicLabels.get(pathname));
    return label ? literalCrumb(pathname, label, current) : null;
}

function shell(crumbs: BreadcrumbCrumb[]): BreadcrumbResolution {
    return { kind: "shell", crumbs };
}

/**
 * The crumb for the Connected accounts destination, as a parent rather than as the current page.
 *
 * Its name is the manifest group's, resolved the way {@link canonicalSettingsTrail} resolves every
 * other canonical destination's, rather than a `CommonBreadcrumb` string of its own. The
 * capture-review resolver is the only address that needs this destination as a middle crumb, and a
 * second copy of its name would be one more place for the two to drift.
 */
function connectedAccountsCrumb(context: BreadcrumbRouteContext): BreadcrumbCrumb {
    const group = MANIFEST_GROUPS.find(
        (candidate) => candidate.id === "personal.connected-accounts",
    );
    return literalCrumb(
        group?.route ?? CONNECTED_ACCOUNTS_ROUTE,
        context.translateMessage(group?.titleKey ?? ""),
    );
}

/**
 * The trail for a canonical settings destination, or null when the reader may not be there.
 *
 * The scope decides the root, exhaustively: an organization destination is rooted in the
 * organization and refuses a reader holding no role there, exactly as the legacy organization
 * routes do; a workspace destination is rooted in the active workspace; a personal one is rooted in
 * Settings alone, because nothing about it belongs to the workspace the reader happens to be in.
 */
function canonicalSettingsTrail(
    pathname: string,
    group: SettingsGroup,
    context: BreadcrumbRouteContext,
): BreadcrumbResolution | null {
    const current = literalCrumb(pathname, context.translateMessage(group.titleKey ?? ""), true);
    const settings = translatedCrumb(SETTINGS_HOME_ROUTE, "settings", context);
    switch (group.scope) {
        case "organization":
            return context.organizationAccessible
                ? shell([organizationRoot(context), settings, current])
                : null;
        case "workspace":
            return shell(withWorkspace(context, [settings, current]));
        case "personal":
            return shell([settings, current]);
        default: {
            const unreachable: never = group.scope;
            return unreachable;
        }
    }
}

function empty(kind: Exclude<BreadcrumbResolution["kind"], "shell">): BreadcrumbResolution {
    return { kind, crumbs: [] };
}

/** Resolves an authenticated pathname from canonical route structure and live identity state. */
export function resolveBreadcrumbRoute(
    pathname: string,
    context: BreadcrumbRouteContext,
): BreadcrumbResolution {
    if (!pathname.startsWith("/") || pathname.includes("?") || pathname.includes("#")) {
        return empty("unknown");
    }
    if (REDIRECT_ROUTES.has(pathname) || LEGACY_WORKFLOW_REDIRECT.test(pathname) || REPORT_SNAPSHOTS_REDIRECT.test(pathname)) {
        return empty("redirect");
    }
    if ((pathname === "/workflows" || pathname.startsWith("/workflows/")) && !context.navAccess.workflows) {
        return empty("denied");
    }
    if (OWNED_ROUTE_PATTERNS.some((pattern) => pattern.test(pathname))) return empty("owned");
    if (pathname === "/overview/reports/goals") {
        if (!context.navAccess.goals) return empty("denied");
        return shell(withWorkspace(context, [
            translatedCrumb("/overview/reports", "reports", context),
            translatedCrumb(pathname, "goals", context, true),
        ]));
    }

    const staticRoute = STATIC_WORKSPACE_ROUTES[pathname];
    if (staticRoute) {
        if (staticRoute.access && !context.navAccess[staticRoute.access]) return empty("denied");
        const current = translatedCrumb(pathname, staticRoute.key, context, true);
        if (pathname === "/dashboard") {
            const root = workspaceRoot(context);
            return shell(root ? [{ ...root, current: true }] : [current]);
        }
        return shell(withWorkspace(context, [current]));
    }

    if (pathname === SETTINGS_HOME_ROUTE) {
        return shell(withWorkspace(context, [
            translatedCrumb(SETTINGS_HOME_ROUTE, "settings", context, true),
        ]));
    }

    const canonicalGroup = CANONICAL_SETTINGS_GROUPS.get(pathname);
    if (canonicalGroup) {
        const trail = canonicalSettingsTrail(pathname, canonicalGroup, context);
        if (trail !== null) return trail;
        return empty("denied");
    }

    const settingsRoute = SETTINGS_ROUTES[pathname];
    if (settingsRoute) {
        if (settingsRoute.access && !context.navAccess[settingsRoute.access]) return empty("denied");
        return shell(withWorkspace(context, [
            translatedCrumb("/settings", "settings", context),
            translatedCrumb(pathname, settingsRoute.key, context, true),
        ]));
    }

    if (pathname === "/account/connections/reviews") {
        if (context.navAccess.captureReviews === "disabled") return empty("denied");
        return shell([
            translatedCrumb(SETTINGS_HOME_ROUTE, "settings", context),
            connectedAccountsCrumb(context),
            translatedCrumb(pathname, "captureReviews", context, true),
        ]);
    }

    const organizationRoute = ORGANIZATION_ROUTES[pathname];
    if (organizationRoute) {
        if (!context.organizationAccessible) return empty("denied");
        return shell([
            organizationRoot(context),
            translatedCrumb(pathname, organizationRoute, context, true),
        ]);
    }

    const recordMatch = DYNAMIC_RECORD_ROUTE.exec(pathname);
    if (recordMatch) {
        const metadata = RECORD_ROUTE_METADATA[recordMatch[1]];
        if (!metadata) return empty("unknown");
        const entity = dynamicCrumb(pathname, context, true);
        return shell(withWorkspace(context, [
            translatedCrumb(
                metadata.collectionPath,
                metadata.collectionKey,
                context,
                false,
                metadata.returnCollection,
            ),
            ...(entity ? [entity] : []),
        ]));
    }

    if (USER_ROUTE.test(pathname)) {
        const user = dynamicCrumb(pathname, context, true);
        return shell(withWorkspace(context, [
            translatedCrumb("/users", "users", context),
            ...(user ? [user] : []),
        ]));
    }

    if (CAMPAIGN_ROUTE.test(pathname)) {
        if (!context.navAccess.campaigns) return empty("denied");
        const campaign = dynamicCrumb(pathname, context, true);
        return shell(withWorkspace(context, [
            translatedCrumb("/marketing/campaigns", "campaigns", context),
            ...(campaign ? [campaign] : []),
        ]));
    }

    const reportMatch = REPORT_ROUTE.exec(pathname);
    if (reportMatch) {
        const report = dynamicCrumb(pathname, context, true);
        return shell(withWorkspace(context, [
            translatedCrumb("/overview/reports", "reports", context),
            ...(report ? [report] : []),
        ]));
    }

    const reportEditMatch = REPORT_EDIT_ROUTE.exec(pathname);
    if (reportEditMatch) {
        const reportPath = `/overview/reports/${reportEditMatch[1]}`;
        const report = dynamicCrumb(reportPath, context, false);
        return shell(withWorkspace(context, [
            translatedCrumb("/overview/reports", "reports", context),
            ...(report ? [report] : []),
            translatedCrumb(pathname, "edit", context, true),
        ]));
    }

    const reportSnapshotMatch = REPORT_SNAPSHOT_ROUTE.exec(pathname);
    if (reportSnapshotMatch) {
        const reportPath = `/overview/reports/${reportSnapshotMatch[1]}`;
        const report = dynamicCrumb(reportPath, context, false);
        return shell(withWorkspace(context, [
            translatedCrumb("/overview/reports", "reports", context),
            ...(report ? [report] : []),
            translatedCrumb(pathname, "snapshot", context, true),
        ]));
    }

    const workflowRunMatch = WORKFLOW_RUN_ROUTE.exec(pathname);
    if (workflowRunMatch) {
        const workflowPath = `/workflows/${workflowRunMatch[1]}`;
        const workflow = dynamicCrumb(workflowPath, context, false);
        return shell(withWorkspace(context, [
            translatedCrumb("/workflows", "workflows", context),
            ...(workflow ? [workflow] : []),
            translatedCrumb(pathname, "run", context, true),
        ]));
    }

    if (pathname === "/workflows/operations") {
        return shell(withWorkspace(context, [
            translatedCrumb("/workflows", "workflows", context),
            translatedCrumb(pathname, "operations", context, true),
        ]));
    }
    if (pathname === "/workflows/recipes") {
        return shell(withWorkspace(context, [
            translatedCrumb("/workflows", "workflows", context),
            translatedCrumb(pathname, "recipes", context, true),
        ]));
    }

    const recipeMatch = WORKFLOW_RECIPE_ROUTE.exec(pathname);
    if (recipeMatch && isWorkflowRecipeKey(recipeMatch[1] ?? "")) {
        const recipe = dynamicCrumb(pathname, context, true);
        return shell(withWorkspace(context, [
            translatedCrumb("/workflows", "workflows", context),
            translatedCrumb("/workflows/recipes", "recipes", context),
            ...(recipe ? [recipe] : []),
        ]));
    }

    if (pathname === "/overview/reports/new") {
        return shell(withWorkspace(context, [
            translatedCrumb("/overview/reports", "reports", context),
            translatedCrumb(pathname, "newReport", context, true),
        ]));
    }

    return empty("unknown");
}

/** Collapses a resolved trail without discarding the hidden links from the overflow menu. */
export function buildBreadcrumbNodes(
    crumbs: BreadcrumbCrumb[],
    mode: BreadcrumbDisplayMode,
): BreadcrumbNode[] {
    const visibleLimit = mode === "mobile" ? 2 : 4;
    if (crumbs.length <= visibleLimit) {
        return crumbs.map((crumb) => ({ kind: "crumb", crumb }));
    }
    if (mode === "mobile") {
        return [
            { kind: "ellipsis", hidden: crumbs.slice(0, -2) },
            { kind: "crumb", crumb: crumbs[crumbs.length - 2] },
            { kind: "crumb", crumb: crumbs[crumbs.length - 1] },
        ];
    }
    return [
        { kind: "crumb", crumb: crumbs[0] },
        { kind: "ellipsis", hidden: crumbs.slice(1, -2) },
        { kind: "crumb", crumb: crumbs[crumbs.length - 2] },
        { kind: "crumb", crumb: crumbs[crumbs.length - 1] },
    ];
}
