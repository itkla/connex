import type {
    MyWorkspaces,
    OrganizationIdentity,
    Workspace,
    WorkspaceIdentity,
} from "@/app/lib/types";

/**
 * Merges a freshly arrived server payload over the workspaces currently published to the tree.
 *
 * The payload wins, except for workspaces the server has never mentioned. `WorkspaceProvider`'s
 * `create` and pending-membership acceptance publish their returned workspace before the refresh
 * that will report it, so a server render that began earlier can arrive with that workspace
 * missing. Dropping it there would not merely flicker: the active workspace is already the new one,
 * so it would name a workspace absent from the list and `activeWorkspace` would resolve to null,
 * leaving the shell with no active workspace until some later refresh happened to repair it.
 *
 * Absence is only treated as an addition when the previously consumed payload did not mention the
 * workspace either. A workspace that was in the last payload and is gone from this one was removed
 * — the viewer left it, or lost their membership — and must not be resurrected.
 *
 * @param held - the workspaces currently published to the tree
 * @param consumed - the payload those were last reconciled against
 * @param arriving - the payload just received from the server
 * @returns the workspaces to publish, or `arriving` itself when nothing had to be held back
 */
export function adoptWorkspaces(
    held: readonly Workspace[],
    consumed: readonly Workspace[],
    arriving: Workspace[],
): Workspace[] {
    const consumedIds = new Set(consumed.map((workspace) => workspace.id));
    const arrivingIds = new Set(arriving.map((workspace) => workspace.id));
    const unacknowledged = held.filter(
        (workspace) => !arrivingIds.has(workspace.id) && !consumedIds.has(workspace.id),
    );
    return unacknowledged.length === 0 ? arriving : [...arriving, ...unacknowledged];
}

/** Applies a canonical workspace identity without disturbing the viewer's membership snapshot. */
export function applyWorkspaceIdentity(
    workspaces: readonly Workspace[],
    identity: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
): Workspace[] {
    return workspaces.map((workspace) => workspace.id === identity.id
        ? { ...workspace, name: identity.name, slug: identity.slug, timezone: identity.timezone }
        : workspace);
}

/** Restores an optimistic identity only while that exact optimistic value is still published. */
export function restoreWorkspaceIdentity(
    workspaces: Workspace[],
    expected: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
    replacement: Pick<WorkspaceIdentity, "id" | "name" | "slug" | "timezone">,
): Workspace[] {
    if (expected.id !== replacement.id) return workspaces;
    let restored = false;
    const next = workspaces.map((workspace) => {
        if (workspace.id !== expected.id
            || workspace.name !== expected.name
            || workspace.slug !== expected.slug
            || workspace.timezone !== expected.timezone) {
            return workspace;
        }
        restored = true;
        return {
            ...workspace,
            name: replacement.name,
            slug: replacement.slug,
            timezone: replacement.timezone,
        };
    });
    return restored ? next : workspaces;
}

/** Resolves the active workspace reporting timezone with the account timezone as its fallback. */
export function resolveWorkspaceTimezone(snapshot: MyWorkspaces, accountTimezone: string): string {
    return snapshot.workspaces.find(({ id }) => id === snapshot.activeWorkspaceId)?.timezone
        ?? accountTimezone;
}

/** Applies an organization display identity to every workspace belonging to that organization. */
export function applyOrganizationIdentity(
    workspaces: readonly Workspace[],
    identity: Pick<OrganizationIdentity, "id" | "name">,
): Workspace[] {
    return workspaces.map((workspace) => workspace.orgId === identity.id
        ? { ...workspace, orgName: identity.name }
        : workspace);
}
