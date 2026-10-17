/**
 * The workspace the mounted app shell renders, published by `WorkspaceProvider` so the shared
 * request pipeline can pin every state-changing request to it (#1732).
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
