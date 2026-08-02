/**
 * Route inventory for the Wave 4 (#856) route/state matrix.
 *
 * Tiers exist because the full cross-product of routes × viewports × locales × themes × states runs
 * to roughly 1,700 cells, which buys little over a tiered pass: the release-loop core earns the full
 * matrix, the surrounding surfaces earn a representative slice, and the long tail earns a breadth
 * sweep that still catches a broken render, a console error or a missing accessible name.
 */

/** How much of the axis cross-product a route is exercised against. */
export type RouteTier = 1 | 2 | 3;

/** The product area a route belongs to, mirroring the areas #856 names. */
export type RouteArea =
    | 'home'
    | 'contacts'
    | 'companies'
    | 'deals'
    | 'tasks'
    | 'notifications'
    | 'calendar'
    | 'import'
    | 'settings'
    | 'connections'
    | 'diagnostics'
    | 'workflows'
    | 'ai'
    | 'other';

/** The lowest seeded role expected to render the route without a denial. */
export type RouteRole = 'owner' | 'admin' | 'member';

export type MatrixRoute = {
    /** Stable identifier used in artifact filenames and the manifest. */
    id: string;
    /** Concrete URL, with dynamic segments already resolved against seeded ids. */
    path: string;
    area: RouteArea;
    tier: RouteTier;
    /** Role the sweep signs in as; also the role the route is expected to be readable by. */
    role: RouteRole;
    /** Set when the route is expected to deny the seeded member, giving the matrix a real 403 row. */
    deniesMember?: boolean;
};

/**
 * The representative routes. Dynamic segments are bound to the ids the deterministic seeder
 * produces for workspace 1 (`-PseederProfile=small -PseederSeed=853`), so the list is stable across
 * reruns of the same seed.
 */
export const MATRIX_ROUTES: readonly MatrixRoute[] = [
    { id: 'home', path: '/dashboard', area: 'home', tier: 1, role: 'member' },
    { id: 'contacts-browser', path: '/records/contacts', area: 'contacts', tier: 1, role: 'member' },
    { id: 'contact-detail', path: '/records/contacts/1', area: 'contacts', tier: 1, role: 'member' },
    { id: 'companies-browser', path: '/records/companies', area: 'companies', tier: 1, role: 'member' },
    { id: 'company-detail', path: '/records/companies/1', area: 'companies', tier: 1, role: 'member' },
    { id: 'deals-browser', path: '/records/deals', area: 'deals', tier: 1, role: 'member' },
    { id: 'deal-detail', path: '/records/deals/1', area: 'deals', tier: 1, role: 'member' },
    { id: 'pipelines', path: '/records/pipelines', area: 'deals', tier: 1, role: 'member' },
    { id: 'tasks', path: '/activity/tasks', area: 'tasks', tier: 1, role: 'member' },
    { id: 'notifications', path: '/notifications', area: 'notifications', tier: 1, role: 'member' },
    { id: 'calendar', path: '/overview/calendar', area: 'calendar', tier: 1, role: 'member' },
    { id: 'settings-diagnostics', path: '/settings/diagnostics', area: 'diagnostics', tier: 1, role: 'admin', deniesMember: true },

    { id: 'org-diagnostics', path: '/organization/diagnostics', area: 'diagnostics', tier: 2, role: 'owner', deniesMember: true },
    { id: 'connections', path: '/account/connections', area: 'connections', tier: 2, role: 'member' },
    { id: 'connections-reviews', path: '/account/connections/reviews', area: 'import', tier: 2, role: 'member' },
    { id: 'workflows', path: '/workflows', area: 'workflows', tier: 2, role: 'admin', deniesMember: true },
    { id: 'reports', path: '/overview/reports', area: 'other', tier: 2, role: 'member' },
    { id: 'analytics', path: '/overview/analytics', area: 'other', tier: 2, role: 'member' },
    { id: 'introductions', path: '/overview/introductions', area: 'ai', tier: 2, role: 'member' },
    { id: 'settings-root', path: '/settings', area: 'settings', tier: 2, role: 'member' },
    { id: 'settings-members', path: '/settings/members', area: 'settings', tier: 2, role: 'member' },
    { id: 'settings-roles', path: '/settings/roles', area: 'settings', tier: 2, role: 'owner', deniesMember: true },
    { id: 'admin-logs', path: '/admin/logs', area: 'settings', tier: 2, role: 'admin', deniesMember: true },
    { id: 'products', path: '/records/products', area: 'deals', tier: 2, role: 'admin', deniesMember: true },
    { id: 'activity-all', path: '/activity/all', area: 'tasks', tier: 2, role: 'member' },
    { id: 'notes', path: '/activity/notes', area: 'tasks', tier: 2, role: 'member' },
    { id: 'files', path: '/library/files', area: 'other', tier: 2, role: 'member' },
    { id: 'campaigns', path: '/marketing/campaigns', area: 'other', tier: 2, role: 'member' },
    { id: 'me', path: '/me', area: 'tasks', tier: 2, role: 'member' },
    { id: 'organization', path: '/organization', area: 'settings', tier: 2, role: 'owner' },
    { id: 'search', path: '/search', area: 'other', tier: 2, role: 'member' },

    { id: 'account', path: '/account', area: 'settings', tier: 3, role: 'member' },
    { id: 'account-profile', path: '/account/profile', area: 'settings', tier: 3, role: 'member' },
    { id: 'account-security', path: '/account/security', area: 'settings', tier: 3, role: 'member' },
    { id: 'account-notifications', path: '/account/notifications', area: 'settings', tier: 3, role: 'member' },
    { id: 'account-invites', path: '/account/invites', area: 'settings', tier: 3, role: 'member' },
    { id: 'documents', path: '/library/documents', area: 'other', tier: 3, role: 'member' },
    { id: 'tags', path: '/library/tags', area: 'other', tier: 3, role: 'member' },
    { id: 'map', path: '/overview/map', area: 'other', tier: 3, role: 'member' },
    { id: 'goals', path: '/overview/reports/goals', area: 'other', tier: 3, role: 'member' },
    { id: 'approval-policies', path: '/records/approval-policies', area: 'other', tier: 3, role: 'admin' },
    { id: 'custom-fields', path: '/settings/custom-fields', area: 'settings', tier: 3, role: 'admin', deniesMember: true },
    { id: 'settings-data', path: '/settings/data', area: 'import', tier: 3, role: 'admin' },
    { id: 'settings-email', path: '/settings/email', area: 'settings', tier: 3, role: 'admin', deniesMember: true },
    { id: 'settings-delivery', path: '/settings/delivery', area: 'settings', tier: 3, role: 'admin', deniesMember: true },
    { id: 'settings-notifications', path: '/settings/notifications', area: 'settings', tier: 3, role: 'member' },
    { id: 'settings-security', path: '/settings/security', area: 'settings', tier: 3, role: 'admin' },
    { id: 'settings-membership', path: '/settings/membership', area: 'settings', tier: 3, role: 'member' },
    { id: 'settings-rules', path: '/settings/rules', area: 'workflows', tier: 3, role: 'admin', deniesMember: true },
    { id: 'settings-sso', path: '/settings/sso', area: 'settings', tier: 3, role: 'admin' },
    { id: 'org-members', path: '/organization/members', area: 'settings', tier: 3, role: 'owner' },
    { id: 'org-audit', path: '/organization/audit', area: 'settings', tier: 3, role: 'owner' },
    { id: 'org-ai', path: '/organization/ai', area: 'ai', tier: 3, role: 'owner' },
    { id: 'org-sso', path: '/organization/sso', area: 'settings', tier: 3, role: 'owner' },
    { id: 'org-domains', path: '/organization/allowed-domains', area: 'settings', tier: 3, role: 'owner' },
    { id: 'org-dsr', path: '/organization/data-requests', area: 'settings', tier: 3, role: 'owner' },
];

/** Routes at or above the given tier, ordered as declared. */
export function routesForTier(tier: RouteTier): readonly MatrixRoute[] {
    return MATRIX_ROUTES.filter((route) => route.tier <= tier);
}
