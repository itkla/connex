/**
 * The workspace the mounted app shell renders, published by `WorkspaceProvider` so the shared
 * request pipeline can pin every workspace-bound write to it (#1732).
 *
 * The `connex_workspace` cookie is not that workspace. After a remembered workspace is revoked, the
 * first safe read heals the cookie to another membership (#1108), while the shell keeps rendering
 * the revoked one until it reloads, because the provider never adopts its active workspace from a
 * refreshed payload. Anything the user composed on that page belongs to the rendered workspace, so a
 * write that followed the healed cookie would land in a different organization (#1649). Pinning to
 * the rendered workspace makes the server's explicit-pin refusal answer instead.
 *
 * Only browser code binds. On the server every reader sees no binding, so module state shared
 * between concurrent server renders never carries one viewer's workspace into another's request.
 */

type RenderedWorkspaceBinding = {
    owner: object;
    workspaceId: number;
};

let binding: RenderedWorkspaceBinding | null = null;
const workspaceNames = new Map<number, string>();

function isWorkspaceId(value: number): boolean {
    return Number.isSafeInteger(value) && value > 0 && value <= 2_147_483_647;
}

/**
 * Publishes the workspace `owner` renders, replacing any earlier binding. Publishing `null` clears
 * the binding only while `owner` still holds it, which returns state-changing requests to the
 * cookie: a shell with no active workspace withholds its workspace-scoped children anyway.
 * @param owner the provider instance publishing the selection
 * @param workspaceId the workspace it renders, or null when it renders none
 */
export function bindRenderedWorkspace(owner: object, workspaceId: number | null): void {
    if (typeof window === "undefined") return;
    if (workspaceId !== null && isWorkspaceId(workspaceId)) {
        binding = { owner, workspaceId };
    } else if (binding?.owner === owner) {
        binding = null;
    }
}

/**
 * Removes `owner`'s binding and the workspace names it recorded, leaving another owner's untouched.
 * @param owner the provider instance that is unmounting
 */
export function releaseRenderedWorkspace(owner: object): void {
    if (binding?.owner !== owner) return;
    binding = null;
    workspaceNames.clear();
}

/** Returns the workspace the mounted shell renders, or null when no shell has published one. */
export function renderedWorkspaceId(): number | null {
    return binding?.workspaceId ?? null;
}

/**
 * Records the names of the workspaces the shell lists, so a refusal can still name a workspace after
 * a refreshed membership list has dropped it.
 * @param workspaces the workspaces the shell currently lists
 */
export function rememberWorkspaceNames(workspaces: readonly { id: number; name: string }[]): void {
    if (typeof window === "undefined") return;
    for (const workspace of workspaces) {
        if (isWorkspaceId(workspace.id) && workspace.name.trim()) {
            workspaceNames.set(workspace.id, workspace.name);
        }
    }
}

/**
 * Returns the name the viewer last saw for a workspace, or null when the shell never listed it.
 * @param workspaceId the workspace to name
 */
export function rememberedWorkspaceName(workspaceId: number): string | null {
    return workspaceNames.get(workspaceId) ?? null;
}

/**
 * The state-changing routes that keep their previous selection behaviour, following the
 * `connex_workspace` cookie, instead of being pinned to the rendered workspace (#1732). Everything
 * else under `/api` is workspace-bound and pinned. Each group mirrors a backend source, and
 * `test/unit/workspaceBoundSubmission.test.tsx` fails when either drifts:
 *
 * - `interceptorExcluded` is `WebConfig`'s `excludePathPatterns` list, verbatim: those routes never
 *   pass through `TenantResolutionInterceptor`, so no workspace is resolved for them.
 * - `interceptorSelfResolved` expands the interceptor's own early exits: its `SELECTION_PATH`
 *   selection and link-bootstrap routes, the ownership-offer decisions and the organization and
 *   workspace lifecycle deletes. Each authorizes the target it names itself; shell workspace
 *   creation supplies its explicit origin separately, and the server validates membership there.
 * - `accountAndOrganization` is the account and organization families, and the personal provider
 *   connection controls (connect, pause, resume, disconnect, native pairing). Their target is the signed-in user or
 *   the organization named in the path, and the API surface ledger shows them authorized by user,
 *   organization, `ProviderConnectionService` and `NativeConnectService` code rather than by a workspace permission, so a
 *   revoked rendered workspace must not refuse them before that authorization runs. The other
 *   connection routes (capture review, approval, sync, capture purges and policies) write workspace
 *   content and stay pinned.
 *
 * Patterns use the backend's path syntax: `{id}` is one decimal segment, any other `{name}` is one
 * non-empty segment, and a trailing `/**` matches the path itself and anything beneath it.
 */
export const UNPINNED_WRITE_ROUTES = {
    interceptorExcluded: [
        "/api/auth/**",
        "/api/mail/managed",
        "/api/health",
        "/api/health/ready",
        "/api/metrics",
        "/api/version",
        "/api/capabilities",
        "/api/csp-reports",
    ],
    interceptorSelfResolved: [
        "/api/workspaces",
        "/api/workspaces/{id}/switch",
        "/api/workspaces/{id}/accept",
        "/api/workspaces/{id}/decline",
        "/api/workspaces/{id}/leave",
        "/api/orgs/{id}/leave",
        "/api/invites/exchange",
        "/api/invites/accept",
        "/api/invite-links/exchange",
        "/api/invite-links/accept",
        "/api/delivery/unsubscribe/exchange",
        "/api/document-acceptance/exchange",
        "/api/ownership-offers/{id}/accept",
        "/api/ownership-offers/{id}/decline",
        "/api/orgs/{id}",
        "/api/orgs/{id}/workspaces/{id}",
    ],
    accountAndOrganization: [
        "/api/users/me/**",
        "/api/notification-preferences/**",
        "/api/orgs/{id}/**",
        "/api/account/connections/{provider}",
        "/api/account/connections/{provider}/authorize",
        "/api/account/connections/{provider}/pause",
        "/api/account/connections/{provider}/resume",
        "/api/account/connections/native/{provider}/pairing",
    ],
} as const satisfies Record<string, readonly string[]>;

const UNPINNED_WRITE_PATTERNS: readonly string[] = Object.values(UNPINNED_WRITE_ROUTES).flat();

/**
 * Whether a path matches one of {@link UNPINNED_WRITE_ROUTES}' patterns.
 * @param pattern a route pattern in the backend's path syntax
 * @param path a request path without its query string
 */
export function matchesRoutePattern(pattern: string, path: string): boolean {
    const patternSegments = pattern.split("/");
    const pathSegments = path.split("/");
    const wildcardTail = patternSegments.at(-1) === "**";
    const fixed = wildcardTail ? patternSegments.slice(0, -1) : patternSegments;
    if (wildcardTail ? pathSegments.length < fixed.length : pathSegments.length !== fixed.length) {
        return false;
    }
    return fixed.every((segment, index) => {
        const actual = pathSegments[index];
        if (segment === "{id}") return /^\d+$/.test(actual);
        if (/^\{[a-zA-Z]+\}$/.test(segment)) return actual.length > 0;
        return segment === actual;
    });
}

/**
 * Whether a state-changing request to `path` is workspace-bound, and so pinned to the rendered
 * workspace and reported as {@code WORKSPACE_UNAVAILABLE} when that workspace refuses it.
 * @param path the request path, with or without a query string
 */
export function pinsToRenderedWorkspace(path: string): boolean {
    const pathname = path.split("?")[0];
    return !UNPINNED_WRITE_PATTERNS.some((pattern) => matchesRoutePattern(pattern, pathname));
}

/**
 * Request options that pin a write to the workspace its content was composed in. A surface that
 * saves later than the user's action (a debounce, an autosave, an unmount flush) captures that
 * workspace when the content is composed and sends it with every save, so a selection change that
 * lands first cannot carry the content into the newly selected workspace.
 * @param workspaceId the workspace the content belongs to, or null when none was rendered
 * @param init further request options to keep
 */
export function workspacePinnedRequest(workspaceId: number | null, init: RequestInit = {}): RequestInit {
    if (workspaceId === null || !isWorkspaceId(workspaceId)) return init;
    const headers = new Headers(init.headers);
    headers.set("X-Workspace-Id", String(workspaceId));
    return { ...init, headers };
}
