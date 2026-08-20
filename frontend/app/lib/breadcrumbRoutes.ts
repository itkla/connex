import type { NavAccess } from "@/app/lib/navAccess";
import type { RecordCollection } from "@/app/lib/recordReturnPath";
import { SETTINGS_HOME_ROUTE } from "@/app/lib/settingsManifest";
import { isWorkflowRecipeKey } from "@/app/lib/workflowOperations";

export type BreadcrumbMessageKey =
    | "account"
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
    | "connections"
    | "contacts"
    | "customFields"
    | "dashboard"
    | "data"
    | "dataRequests"
    | "deals"
    | "delivery"
    | "diagnostics"
    | "documents"
    | "edit"
    | "email"
    | "files"
    | "general"
    | "goals"
    | "introductions"
    | "invites"
    | "map"
    | "members"
    | "newReport"
    | "notes"
    | "notifications"
    | "operations"
    | "organization"
    | "overview"
    | "peopleAccess"
    | "pipelines"
    | "products"
    | "profile"
    | "qualification"
    | "radar"
    | "recipes"
    | "reports"
    | "roles"
    | "run"
    | "search"
    | "security"
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
    "/me": { key: "profile" },
    "/users": { key: "users" },
    "/workflows": { key: "workflows", access: "workflows" },
    "/admin/logs": { key: "auditLog", access: "auditLog" },
};

const SETTINGS_ROUTES: Readonly<Record<string, StaticWorkspaceRoute>> = {
    "/settings/general": { key: "general", access: "diagnostics" },
    "/settings/members": { key: "members" },
    "/settings/roles": { key: "roles" },
    "/settings/custom-fields": { key: "customFields" },
    "/settings/qualification": { key: "qualification" },
    "/settings/data": { key: "data" },
    "/settings/email": { key: "email" },
    "/settings/delivery": { key: "delivery" },
    "/settings/diagnostics": { key: "diagnostics", access: "diagnostics" },
    "/settings/workspace/people": { key: "peopleAccess" },
};

const ACCOUNT_ROUTES: Readonly<Record<string, BreadcrumbMessageKey>> = {
    "/account/profile": "profile",
    "/account/security": "security",
    "/account/connections": "connections",
    "/account/notifications": "notifications",
    "/account/invites": "invites",
};

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
    ...Object.keys(ACCOUNT_ROUTES),
    ...Object.keys(ORGANIZATION_ROUTES),
    "/account/connections/reviews",
    "/overview/reports/goals",
    "/overview/reports/new",
    "/workflows/operations",
    "/workflows/recipes",
])].sort();

const REDIRECT_ROUTES = new Set([
    "/account",
    "/organization",
    "/settings/membership",
    "/settings/notifications",
    "/settings/rules",
    "/settings/security",
    "/settings/sso",
]);

const OWNED_ROUTE_PATTERNS = [
    /^\/activity\/notes\/new$/,
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

    const settingsRoute = SETTINGS_ROUTES[pathname];
    if (settingsRoute) {
        if (settingsRoute.access && !context.navAccess[settingsRoute.access]) return empty("denied");
        return shell(withWorkspace(context, [
            translatedCrumb("/settings", "settings", context),
            translatedCrumb(pathname, settingsRoute.key, context, true),
        ]));
    }

    const accountRoute = ACCOUNT_ROUTES[pathname];
    if (accountRoute) {
        return shell([
            translatedCrumb("/account", "account", context),
            translatedCrumb(pathname, accountRoute, context, true),
        ]);
    }
    if (pathname === "/account/connections/reviews") {
        if (context.navAccess.captureReviews === "disabled") return empty("denied");
        return shell([
            translatedCrumb("/account", "account", context),
            translatedCrumb("/account/connections", "connections", context),
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
