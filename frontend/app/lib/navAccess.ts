import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * Whether the viewer may reach the navigation destinations that are gated on a capability or an
 * effective permission. Resolved once on the server so the sidebar and the command palette gate
 * identically; every field is fail-closed, so a failed lookup hides the entry rather than surfacing
 * a destination the backend will reject.
 */
export type NavAccess = {
    /** Goals live under Reports and require {@code GOAL_READ}. */
    goals: boolean;
    /** The workspace audit log requires {@code AUDIT_READ}. */
    auditLog: boolean;
    /** The connected-capture review queue requires at least one capture-enabled provider. */
    captureReviews: boolean;
    /**
     * Campaigns require {@code CAMPAIGN_VIEW}. Gated on the permission alone, not on the
     * {@code campaignDelivery} capability: planning a campaign, defining its audience and authoring
     * its messages all work on an instance that cannot dispatch, so hiding the section there would
     * hide working functionality rather than a broken promise.
     */
    campaigns: boolean;
    /** Workflow definitions, recipes, and operations require {@code RULE_MANAGE}. */
    workflows: boolean;
    /**
     * Workspace diagnostics require {@code WORKSPACE_SETTINGS}, the permission its endpoints
     * enforce. Gating on the role-derived owner/admin approximation instead would hide the page
     * from a custom role that holds the permission without being an administrator.
     */
    diagnostics: boolean;
};

/** The fail-closed default applied when capabilities or permissions could not be loaded. */
export const NO_NAV_ACCESS: NavAccess = {
    goals: false,
    auditLog: false,
    captureReviews: false,
    campaigns: false,
    workflows: false,
    diagnostics: false,
};

/**
 * Derives the gated navigation entries from the instance capabilities and the viewer's effective
 * permissions.
 *
 * @param capabilities - the resolved instance capabilities
 * @param effectivePermissions - the viewer's effective permission keys
 * @returns the destinations the viewer may see
 */
export function resolveNavAccess(
    capabilities: InstanceCapabilities,
    effectivePermissions: readonly string[],
): NavAccess {
    return {
        goals: effectivePermissions.includes("GOAL_READ"),
        auditLog: effectivePermissions.includes("AUDIT_READ"),
        captureReviews:
            capabilities.connectedCapture.google || capabilities.connectedCapture.microsoft,
        campaigns: effectivePermissions.includes("CAMPAIGN_VIEW"),
        workflows: effectivePermissions.includes("RULE_MANAGE"),
        diagnostics: effectivePermissions.includes("WORKSPACE_SETTINGS"),
    };
}
