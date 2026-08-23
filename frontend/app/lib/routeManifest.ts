import {
    ACTIVITY_URL_KEY,
    COMMENT_URL_KEY,
    NOTE_URL_KEY,
    PIPELINE_EDIT_URL_KEY,
    TASK_URL_KEY,
} from '@/app/hooks/listStateUrl';

/**
 * The complete inventory of shipped authenticated App Router routes, as `/segment/[param]` patterns
 * matching the directory layout under `app/(app)`.
 *
 * This is the committed denominator for the route link-integrity gate (#1338): every in-app href and
 * every backend notification `actionUrl` must resolve to one of these, so a link to a route that was
 * never shipped fails a test instead of a user's click. `frontend/test/unit/routeManifest.test.ts`
 * keeps it reconciled with both the filesystem and the backend copy in
 * `backend/src/test/resources/frontend-route-manifest.json`.
 *
 * Routes rendered outside the authenticated shell — the marketing, docs, legal, and auth surfaces —
 * are deliberately out of scope; nothing generates deep links into them.
 */
export const SHIPPED_APP_ROUTES = [
    '/account',
    '/account/connections',
    '/account/connections/reviews',
    '/account/invites',
    '/account/notifications',
    '/account/profile',
    '/account/security',
    '/activity/activities/[id]',
    '/activity/all',
    '/activity/notes',
    '/activity/notes/[id]',
    '/activity/tasks',
    '/activity/tasks/[id]',
    '/admin/logs',
    '/ask-connex',
    '/ask-connex/[sessionId]',
    '/dashboard',
    '/library/documents',
    '/library/documents/[id]',
    '/library/documents/new',
    '/library/files',
    '/library/tags',
    '/marketing/campaigns',
    '/marketing/campaigns/[id]',
    '/me',
    '/notifications',
    '/organization',
    '/organization/ai',
    '/organization/allowed-domains',
    '/organization/audit',
    '/organization/data-requests',
    '/organization/diagnostics',
    '/organization/members',
    '/organization/overview',
    '/organization/sso',
    '/overview/analytics',
    '/overview/calendar',
    '/overview/introductions',
    '/overview/map',
    '/overview/reports',
    '/overview/reports/[id]',
    '/overview/reports/[id]/edit',
    '/overview/reports/[id]/snapshots',
    '/overview/reports/[id]/snapshots/[snapshotId]',
    '/overview/reports/goals',
    '/overview/reports/new',
    '/radar',
    '/records/approval-policies',
    '/records/companies',
    '/records/companies/[id]',
    '/records/contacts',
    '/records/contacts/[id]',
    '/records/deals',
    '/records/deals/[id]',
    '/records/deals/[id]/documents/[docId]/print',
    '/records/pipelines',
    '/records/products',
    '/search',
    '/settings',
    '/settings/custom-fields',
    '/settings/data',
    '/settings/delivery',
    '/settings/diagnostics',
    '/settings/email',
    '/settings/general',
    '/settings/members',
    '/settings/membership',
    '/settings/notifications',
    '/settings/organization/ai-governance',
    '/settings/organization/audit-diagnostics',
    '/settings/organization/data-requests',
    '/settings/organization/general',
    '/settings/organization/identity',
    '/settings/qualification',
    '/settings/roles',
    '/settings/rules',
    '/settings/security',
    '/settings/sso',
    '/settings/workflows/[legacyRuleId]',
    '/settings/workspace/audit-diagnostics',
    '/settings/workspace/communications',
    '/settings/workspace/crm',
    '/settings/workspace/people',
    '/users',
    '/users/[id]',
    '/workflows',
    '/workflows/[workflowId]',
    '/workflows/[workflowId]/runs/[runKey]',
    '/workflows/new',
    '/workflows/operations',
    '/workflows/recipes',
    '/workflows/recipes/[recipeKey]',
] as const;

/** One shipped route pattern from {@link SHIPPED_APP_ROUTES}. */
export type ShippedAppRoute = (typeof SHIPPED_APP_ROUTES)[number];

/** Query parameters owned by each shipped route's deep-link consumers. */
export const SHIPPED_ROUTE_QUERY_PARAMS = {
    '/activity/all': [ACTIVITY_URL_KEY],
    '/activity/notes': [NOTE_URL_KEY],
    '/activity/tasks': [TASK_URL_KEY],
    '/records/companies/[id]': [ACTIVITY_URL_KEY, COMMENT_URL_KEY, NOTE_URL_KEY, TASK_URL_KEY],
    '/records/contacts/[id]': [ACTIVITY_URL_KEY, COMMENT_URL_KEY, NOTE_URL_KEY, TASK_URL_KEY],
    '/records/deals/[id]': [ACTIVITY_URL_KEY, COMMENT_URL_KEY, NOTE_URL_KEY, TASK_URL_KEY],
    '/records/pipelines': [PIPELINE_EDIT_URL_KEY],
} as const satisfies Partial<Record<ShippedAppRoute, readonly string[]>>;

function isDynamicSegment(segment: string): boolean {
    return segment.startsWith('[') && segment.endsWith(']');
}

function pathSegments(pathname: string): string[] {
    return pathname.split('/').filter((segment) => segment.length > 0);
}

function matches(patternSegments: string[], segments: string[]): boolean {
    if (patternSegments.length !== segments.length) return false;
    return patternSegments.every(
        (patternSegment, index) =>
            isDynamicSegment(patternSegment) ? segments[index].length > 0 : patternSegment === segments[index],
    );
}

/**
 * Resolves an app-relative href to the shipped route pattern that serves it.
 *
 * Query string and fragment are ignored — they carry deep-link params, not routing. A literal segment
 * always beats a dynamic sibling, mirroring how Next.js itself picks `/overview/reports/new` over
 * `/overview/reports/[id]`.
 *
 * @param href - an app-relative href, with or without a query string
 * @returns the matching pattern, or null when no shipped route serves the href
 */
export function resolveShippedRoute(href: string): ShippedAppRoute | null {
    if (!href.startsWith('/')) return null;
    const pathname = href.split('#')[0].split('?')[0];
    const segments = pathSegments(pathname);
    let best: ShippedAppRoute | null = null;
    let bestDynamicCount = Number.POSITIVE_INFINITY;
    for (const route of SHIPPED_APP_ROUTES) {
        const patternSegments = pathSegments(route);
        if (!matches(patternSegments, segments)) continue;
        const dynamicCount = patternSegments.filter(isDynamicSegment).length;
        if (dynamicCount < bestDynamicCount) {
            best = route;
            bestDynamicCount = dynamicCount;
        }
    }
    return best;
}

/** Whether an app-relative href is served by a shipped route. */
export function matchesShippedRoute(href: string): boolean {
    return resolveShippedRoute(href) !== null;
}

/** Whether a query parameter is consumed by the exact shipped route serving an href. */
export function routeAllowsQueryParam(href: string, key: string): boolean {
    const route = resolveShippedRoute(href);
    if (route === null) return false;
    const allowed = SHIPPED_ROUTE_QUERY_PARAMS[route as keyof typeof SHIPPED_ROUTE_QUERY_PARAMS];
    return allowed?.some((candidate) => candidate === key) ?? false;
}
