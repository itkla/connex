import type {
    MyWorkspaces,
    OrganizationIdentity,
    Workspace,
    WorkspaceIdentity,
} from "@/app/lib/types";

export type PublishedWorkspaceIdentity = Pick<
    WorkspaceIdentity,
    "id" | "name" | "slug" | "timezone" | "identityVersion"
>;
export type PublishedOrganizationIdentity = Pick<OrganizationIdentity, "id" | "name" | "identityVersion">;

/**
 * Merges a freshly arrived server payload over the workspaces currently published to the tree.
 *
 * The payload wins for membership state, except for identities carrying a lower version and
 * workspaces the server has never mentioned. `WorkspaceProvider`'s
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
    const heldById = new Map(held.map((workspace) => [workspace.id, workspace]));
    const consumedIds = new Set(consumed.map((workspace) => workspace.id));
    const arrivingIds = new Set(arriving.map((workspace) => workspace.id));
    let preservedIdentity = false;
    const adopted = arriving.map((workspace) => {
        const current = heldById.get(workspace.id);
        if (!current) return workspace;
        const workspaceIdentityIsNewer = current.identityVersion > workspace.identityVersion;
        const organizationIdentityIsNewer = current.orgId === workspace.orgId
            && current.orgIdentityVersion > workspace.orgIdentityVersion;
        if (!workspaceIdentityIsNewer && !organizationIdentityIsNewer) return workspace;
        preservedIdentity = true;
        return {
            ...workspace,
            ...(workspaceIdentityIsNewer ? {
                name: current.name,
                slug: current.slug,
                timezone: current.timezone,
                identityVersion: current.identityVersion,
            } : {}),
            ...(organizationIdentityIsNewer ? {
                orgName: current.orgName,
                orgIdentityVersion: current.orgIdentityVersion,
            } : {}),
        };
    });
    const unacknowledged = held.filter(
        (workspace) => !arrivingIds.has(workspace.id) && !consumedIds.has(workspace.id),
    );
    if (unacknowledged.length > 0) return [...adopted, ...unacknowledged];
    return preservedIdentity ? adopted : arriving;
}

/** Applies a canonical workspace identity without disturbing the viewer's membership snapshot. */
export function applyWorkspaceIdentity(
    workspaces: readonly Workspace[],
    identity: PublishedWorkspaceIdentity,
): Workspace[] {
    return workspaces.map((workspace) => workspace.id === identity.id
        && identity.identityVersion >= workspace.identityVersion
        ? {
            ...workspace,
            name: identity.name,
            slug: identity.slug,
            timezone: identity.timezone,
            identityVersion: identity.identityVersion,
        }
        : workspace);
}

/** Restores an optimistic identity only while that exact optimistic value is still published. */
export function restoreWorkspaceIdentity(
    workspaces: Workspace[],
    expected: PublishedWorkspaceIdentity,
    replacement: PublishedWorkspaceIdentity,
): Workspace[] {
    if (expected.id !== replacement.id) return workspaces;
    let restored = false;
    const next = workspaces.map((workspace) => {
        if (workspace.id !== expected.id
            || workspace.name !== expected.name
            || workspace.slug !== expected.slug
            || workspace.timezone !== expected.timezone
            || workspace.identityVersion !== expected.identityVersion) {
            return workspace;
        }
        restored = true;
        return {
            ...workspace,
            name: replacement.name,
            slug: replacement.slug,
            timezone: replacement.timezone,
            identityVersion: replacement.identityVersion,
        };
    });
    return restored ? next : workspaces;
}

/**
 * Keeps locally published workspace identities authoritative until an arriving server snapshot
 * acknowledges the same version or carries a newer one. Older React Server Component payloads
 * therefore cannot undo an optimistic or canonical mutation that completed after their reads began.
 */
export function preservePublishedWorkspaceIdentities(
    workspaces: Workspace[],
    published: readonly PublishedWorkspaceIdentity[],
): { workspaces: Workspace[]; pending: PublishedWorkspaceIdentity[] } {
    let reconciled = workspaces;
    const pending: PublishedWorkspaceIdentity[] = [];
    const workspacesById = new Map(workspaces.map((workspace) => [workspace.id, workspace]));
    for (const identity of published) {
        const arriving = workspacesById.get(identity.id);
        if (!arriving) continue;
        if (arriving.identityVersion > identity.identityVersion) continue;
        if (arriving.identityVersion === identity.identityVersion
            && arriving.name === identity.name
            && arriving.slug === identity.slug
            && arriving.timezone === identity.timezone) {
            continue;
        }
        pending.push(identity);
        reconciled = applyWorkspaceIdentity(reconciled, identity);
    }
    return { workspaces: reconciled, pending };
}

/** Resolves the active workspace reporting timezone with the account timezone as its fallback. */
export function resolveWorkspaceTimezone(snapshot: MyWorkspaces, accountTimezone: string): string {
    return snapshot.workspaces.find(({ id }) => id === snapshot.activeWorkspaceId)?.timezone
        ?? accountTimezone;
}

/** Applies an organization display identity to every workspace belonging to that organization. */
export function applyOrganizationIdentity(
    workspaces: readonly Workspace[],
    identity: PublishedOrganizationIdentity,
): Workspace[] {
    return workspaces.map((workspace) => workspace.orgId === identity.id
        && identity.identityVersion >= workspace.orgIdentityVersion
        ? {
            ...workspace,
            orgName: identity.name,
            orgIdentityVersion: identity.identityVersion,
        }
        : workspace);
}

/** Restores an optimistic organization label only while that exact value remains published. */
export function restoreOrganizationIdentity(
    workspaces: Workspace[],
    expected: PublishedOrganizationIdentity,
    replacement: PublishedOrganizationIdentity,
): Workspace[] {
    if (expected.id !== replacement.id) return workspaces;
    let restored = false;
    const next = workspaces.map((workspace) => {
        if (workspace.orgId !== expected.id
            || workspace.orgName !== expected.name
            || workspace.orgIdentityVersion !== expected.identityVersion) return workspace;
        restored = true;
        return {
            ...workspace,
            orgName: replacement.name,
            orgIdentityVersion: replacement.identityVersion,
        };
    });
    return restored ? next : workspaces;
}

/** Keeps a published organization label until the snapshot acknowledges or supersedes its version. */
export function preservePublishedOrganizationIdentities(
    workspaces: Workspace[],
    published: readonly PublishedOrganizationIdentity[],
): { workspaces: Workspace[]; pending: PublishedOrganizationIdentity[] } {
    const latestByOrganization = new Map<number, { name: string; identityVersion: number }>();
    for (const workspace of workspaces) {
        const latest = latestByOrganization.get(workspace.orgId);
        if (!latest || workspace.orgIdentityVersion > latest.identityVersion) {
            latestByOrganization.set(workspace.orgId, {
                name: workspace.orgName,
                identityVersion: workspace.orgIdentityVersion,
            });
        }
    }
    let reconciled = workspaces.map((workspace) => {
        const latest = latestByOrganization.get(workspace.orgId);
        return latest && workspace.orgIdentityVersion < latest.identityVersion
            ? {
                ...workspace,
                orgName: latest.name,
                orgIdentityVersion: latest.identityVersion,
            }
            : workspace;
    });
    const pending: PublishedOrganizationIdentity[] = [];
    for (const identity of published) {
        const organizationWorkspaces = reconciled.filter((workspace) => workspace.orgId === identity.id);
        if (organizationWorkspaces.length === 0) continue;
        if (organizationWorkspaces.some(
            (workspace) => workspace.orgIdentityVersion > identity.identityVersion,
        )) continue;
        if (organizationWorkspaces.every(
            (workspace) => workspace.orgIdentityVersion === identity.identityVersion
                && workspace.orgName === identity.name,
        )) continue;
        pending.push(identity);
        reconciled = applyOrganizationIdentity(reconciled, identity);
    }
    return { workspaces: reconciled, pending };
}
