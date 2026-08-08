import type { OrgRole } from "@/app/lib/types";

export type OrganizationLifecycleAccess = {
    canExport: boolean;
    canTeardown: boolean;
};

/** Derives tenant lifecycle authority only from the organization role. */
export function organizationLifecycleAccess(
    orgRole: OrgRole | null,
): OrganizationLifecycleAccess {
    return {
        canExport: orgRole !== null,
        canTeardown: orgRole === "owner",
    };
}
