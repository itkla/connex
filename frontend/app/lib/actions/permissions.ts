import type { OrgRole, Workspace, WorkspaceRole } from "@/app/lib/types";
import type { PermissionCheck, WorkspaceCapability } from "./types";

const ROLE_GRANTS: Record<WorkspaceRole, ReadonlySet<WorkspaceCapability>> = {
    owner: new Set(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]),
    admin: new Set(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]),
    member: new Set([]),
};

const ORG_ROLE_GRANTS: Record<OrgRole, ReadonlySet<WorkspaceCapability>> = {
    owner: new Set(["ORGANIZATION_VIEW"]),
    admin: new Set(["ORGANIZATION_VIEW"]),
};

/**
 * The capabilities conferred by the only role signals the client actually has — `Workspace.role`
 * and `Workspace.orgRole`.
 *
 * Membership in this set is the whole answer, which is what makes the gate fail closed: a key the
 * client has no rule for is simply absent, so it is refused rather than waved through by a default.
 * Keyed by `string` rather than {@link WorkspaceCapability} on purpose — the set has to answer for
 * any key that reaches it at runtime, and narrowing it here would move that fallback out of reach
 * of a test. Compile-time narrowing belongs on {@link PermissionCheck}, where the typo it prevents
 * is actually written.
 *
 * This is a UX-availability hint; the backend remains authoritative on every call.
 *
 * @param workspace - the active workspace, or null before one is selected
 * @returns the capability keys the viewer's roles confer
 */
export function grantedCapabilities(workspace: Workspace | null): ReadonlySet<string> {
    const granted = new Set<string>();
    const role = workspace?.role;
    const orgRole = workspace?.orgRole ?? null;
    if (role) for (const capability of ROLE_GRANTS[role]) granted.add(capability);
    if (orgRole) for (const capability of ORG_ROLE_GRANTS[orgRole]) granted.add(capability);
    return granted;
}

/**
 * Builds a {@link PermissionCheck} over {@link grantedCapabilities}.
 *
 * @param workspace - the active workspace, or null before one is selected
 * @returns a permission predicate keyed by capability
 */
export function resolveCan(workspace: Workspace | null): PermissionCheck {
    const granted = grantedCapabilities(workspace);
    return (permission) => granted.has(permission);
}
