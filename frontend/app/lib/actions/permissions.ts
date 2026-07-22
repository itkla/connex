import type { OrgRole, Workspace, WorkspaceRole } from "@/app/lib/types";
import type { PermissionCheck } from "./types";

/**
 * Coarse capability keys the action registry gates on today. They follow the backend's `ENTITY_ACTION`
 * catalog spelling so the vocabulary stays consistent when a real effective-permissions endpoint
 * replaces this role-derived approximation.
 */
export const WORKSPACE_CAPABILITIES = ["ORGANIZATION_VIEW", "WORKSPACE_MANAGE", "MEMBER_MANAGE"] as const;

/** A capability the client can approximate from the coarse role signals it already has. */
export type WorkspaceCapability = (typeof WORKSPACE_CAPABILITIES)[number];

const ROLE_GRANTS: Record<WorkspaceRole, ReadonlySet<WorkspaceCapability>> = {
    owner: new Set(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]),
    admin: new Set(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]),
    member: new Set([]),
};

const ORG_ROLE_GRANTS: Record<OrgRole, ReadonlySet<WorkspaceCapability>> = {
    owner: new Set(["ORGANIZATION_VIEW"]),
    admin: new Set(["ORGANIZATION_VIEW"]),
};

const KNOWN_CAPABILITIES: ReadonlySet<string> = new Set(WORKSPACE_CAPABILITIES);

/**
 * Builds a {@link PermissionCheck} from the only role signals the client actually has —
 * `Workspace.role` and `Workspace.orgRole`. This is a UX-availability hint; the backend remains
 * authoritative. Keys outside {@link WORKSPACE_CAPABILITIES} resolve to `true` so the registry never
 * hides an action it cannot yet evaluate.
 *
 * @param workspace - the active workspace, or null before one is selected
 * @returns a permission predicate keyed by capability
 */
export function resolveCan(workspace: Workspace | null): PermissionCheck {
    const granted = new Set<string>();
    const role = workspace?.role;
    const orgRole = workspace?.orgRole ?? null;
    if (role) for (const capability of ROLE_GRANTS[role]) granted.add(capability);
    if (orgRole) for (const capability of ORG_ROLE_GRANTS[orgRole]) granted.add(capability);
    return (permission: string): boolean => (KNOWN_CAPABILITIES.has(permission) ? granted.has(permission) : true);
}
