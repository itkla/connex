import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * The authorization scope that owns a settings destination. The three scopes stay separately
 * authorized and separately owned; #1340 consolidates only how they are presented.
 */
export type SettingsScope = "personal" | "workspace" | "organization";

/** How a manifest entry behaves today, before any route in #1340 moves. */
export type SettingsEntryKind =
    /** Renders a settings surface. */
    | "destination"
    /** Forwards to another settings destination and renders nothing. */
    | "redirect"
    /** Forwards to a destination whose canonical owner is outside the settings tree. */
    | "external-redirect";

/**
 * The four in-place states a capability- or permission-managed destination must be able to explain
 * about itself. A managed destination never silently vanishes or teleports the viewer elsewhere;
 * it stays discoverable and says which of these it is.
 */
export type SettingsAvailabilityState =
    /** "Managed by your Connex instance" — the operator owns this configuration. */
    | "managed"
    /** "Not enabled for this deployment" — the capability is off. */
    | "not-enabled"
    /** "Ask a workspace administrator" — the viewer lacks the permission or the organization role. */
    | "ask-admin"
    /** "Temporarily unavailable — retry" — the capability or permission lookup failed. */
    | "retry";

/** Where a destination is registered in navigation today. */
export type SettingsEntryPoint =
    | "account-tabs"
    | "settings-tabs"
    | "organization-tabs"
    | "sidebar"
    | "avatar-menu"
    | "command-palette"
    | "contextual";

/**
 * A boolean instance-capability key, as a dotted path into {@link InstanceCapabilities}. Derived from
 * the type rather than restated, so a capability that is renamed or dropped fails to compile here
 * instead of silently gating nothing.
 */
export type SettingsCapabilityKey =
    | {
          [K in keyof InstanceCapabilities]: InstanceCapabilities[K] extends boolean ? K : never;
      }[keyof InstanceCapabilities]
    | {
          [K in keyof InstanceCapabilities]: InstanceCapabilities[K] extends {
              google: boolean;
              microsoft: boolean;
          }
              ? `${K & string}.google` | `${K & string}.microsoft`
              : never;
      }[keyof InstanceCapabilities];

/**
 * What a destination requires. `permissions`, `capabilities`, and `orgAdmin` gate reaching the
 * destination at all and therefore drive navigation visibility; `manage` gates only the
 * destination's own configuration writes, so a viewer without it still reaches and reads the page.
 * Keeping the two apart is load-bearing: promoting a `manage` permission to a visibility gate would
 * hide a page that works today.
 */
export type SettingsAccess = {
    /** Backend `Permission` constants required to reach the destination. */
    permissions: readonly string[];
    /** Instance capabilities the destination depends on. */
    capabilities: readonly SettingsCapabilityKey[];
    /** Whether the viewer must hold an organization role. */
    orgAdmin: boolean;
    /** Backend `Permission` constants that gate the destination's configuration writes only. */
    manage: readonly string[];
    /** The in-place states this destination must be able to explain about itself. */
    states: readonly SettingsAvailabilityState[];
};

/**
 * One destination in the target information architecture of #1340 — a row of the scope-grouped
 * Settings navigation. Groups are the unit of canonical ownership: every settings job resolves to
 * exactly one of them.
 */
export type SettingsGroup = {
    /** Stable id, `{scope}.{slug}`. */
    id: string;
    scope: SettingsScope;
    /** The canonical route this group will own. Nothing serves it yet. */
    route: string;
    /** Position within its scope, as the epic lists it. */
    order: number;
    /**
     * The shipped message key whose rendered string already equals {@link epicName}, or null when
     * the shell PR must author the label. Reusing a key is preferred; a null here is a tracked copy
     * task, not an invitation to rename a shipped destination.
     */
    titleKey: string | null;
    /** The group's name in #1340, verbatim, so the copy task is enumerated rather than remembered. */
    epicName: string;
};

/**
 * One settings, account, or administration destination as it exists today, together with where
 * #1340 sends it. Every field describes shipped reality except {@link SettingsEntry.group},
 * {@link SettingsEntry.canonicalRoute}, and {@link SettingsEntry.canonicalSection}, which are the
 * planned mapping and are consumed by nothing yet.
 */
export type SettingsEntry = {
    /** Stable id, independent of both the current and the canonical route. */
    id: string;
    /** The route pattern this destination serves today. */
    currentRoute: string;
    kind: SettingsEntryKind;
    /**
     * The target group that will own this job, or null when the canonical owner is the Settings
     * home itself or a destination outside the settings tree.
     */
    group: string | null;
    /** The route this job resolves to in the target state. */
    canonicalRoute: string;
    /**
     * The section slug within a shared canonical destination, giving the job its stable deep link
     * at `{canonicalRoute}#{canonicalSection}`; null when the entry owns the destination outright.
     */
    canonicalSection: string | null;
    /** Where the server forwards the browser today; null when the entry renders. */
    redirectsTo: string | null;
    /** The shipped message key that labels this destination in navigation; null for a bare stub. */
    titleKey: string | null;
    access: SettingsAccess;
    /** Every navigation surface that links here today. */
    entryPoints: readonly SettingsEntryPoint[];
    /** The message key holding this destination's command-palette aliases, or null. */
    aliasKey: string | null;
};

const NO_ACCESS_REQUIREMENTS: SettingsAccess = {
    permissions: [],
    capabilities: [],
    orgAdmin: false,
    manage: [],
    states: [],
};

/**
 * The route the unified Settings shell will own. It is the one destination that belongs to no scope
 * group: `/settings` lands on a real destination rather than forwarding to an arbitrary tab.
 */
export const SETTINGS_HOME_ROUTE = "/settings";

/**
 * The scope-grouped information architecture of #1340, as data. Later PRs render the navigation,
 * the breadcrumbs, the settings search, and the redirect matrix from this list rather than
 * restating it; nothing consumes it yet.
 */
export const SETTINGS_GROUPS = [
    {
        id: "personal.profile",
        scope: "personal",
        route: "/settings/personal/profile",
        order: 1,
        titleKey: "Account.tabProfile",
        epicName: "Profile",
    },
    {
        id: "personal.security",
        scope: "personal",
        route: "/settings/personal/security",
        order: 2,
        titleKey: "Account.tabSecurity",
        epicName: "Security",
    },
    {
        id: "personal.connected-accounts",
        scope: "personal",
        route: "/settings/personal/connected-accounts",
        order: 3,
        titleKey: "AccountConnections.title",
        epicName: "Connected accounts",
    },
    {
        id: "personal.notifications",
        scope: "personal",
        route: "/settings/personal/notifications",
        order: 4,
        titleKey: null,
        epicName: "Notification preferences",
    },
    {
        id: "personal.workspaces",
        scope: "personal",
        route: "/settings/personal/workspaces",
        order: 5,
        titleKey: null,
        epicName: "Workspaces & invitations",
    },
    {
        id: "workspace.general",
        scope: "workspace",
        route: "/settings/workspace/general",
        order: 1,
        titleKey: "WorkspaceSettings.tabGeneral",
        epicName: "General",
    },
    {
        id: "workspace.people",
        scope: "workspace",
        route: "/settings/workspace/people",
        order: 2,
        titleKey: null,
        epicName: "People & access",
    },
    {
        id: "workspace.crm",
        scope: "workspace",
        route: "/settings/workspace/crm",
        order: 3,
        titleKey: null,
        epicName: "CRM configuration",
    },
    {
        id: "workspace.communications",
        scope: "workspace",
        route: "/settings/workspace/communications",
        order: 4,
        titleKey: null,
        epicName: "Communications",
    },
    {
        id: "workspace.data-privacy",
        scope: "workspace",
        route: "/settings/workspace/data-privacy",
        order: 5,
        titleKey: null,
        epicName: "Data & privacy",
    },
    {
        id: "workspace.audit-diagnostics",
        scope: "workspace",
        route: "/settings/workspace/audit-diagnostics",
        order: 6,
        titleKey: null,
        epicName: "Audit & diagnostics",
    },
    {
        id: "organization.overview",
        scope: "organization",
        route: "/settings/organization/overview",
        order: 1,
        titleKey: "Organization.tabOverview",
        epicName: "Overview",
    },
    {
        id: "organization.identity",
        scope: "organization",
        route: "/settings/organization/identity",
        order: 2,
        titleKey: null,
        epicName: "Identity & administrators",
    },
    {
        id: "organization.ai-governance",
        scope: "organization",
        route: "/settings/organization/ai-governance",
        order: 3,
        titleKey: null,
        epicName: "AI & data governance",
    },
    {
        id: "organization.data-requests",
        scope: "organization",
        route: "/settings/organization/data-requests",
        order: 4,
        titleKey: "Organization.tabDataRequests",
        epicName: "Data requests",
    },
    {
        id: "organization.audit-diagnostics",
        scope: "organization",
        route: "/settings/organization/audit-diagnostics",
        order: 5,
        titleKey: null,
        epicName: "Audit & diagnostics",
    },
] as const satisfies readonly SettingsGroup[];

/**
 * Every routed settings, account, and administration destination Connex ships today, and where
 * #1340 sends it.
 *
 * This is the committed denominator for the settings navigation gate:
 * `frontend/test/unit/settingsManifest.test.ts` reconciles it against the filesystem, the message
 * catalogs, the backend `Permission` enum, and the navigation surfaces that link here, so a new
 * settings page that nobody registered fails a test instead of drifting into a fourth competing
 * configuration universe.
 *
 * `/users`, `/admin/logs`, and `/records/approval-policies` are listed even though they sit outside
 * the three settings roots: #1340 folds each of them into a scope group, so their planned owner
 * belongs here with the rest.
 */
export const SETTINGS_ENTRIES = [
    {
        id: "account.home",
        currentRoute: "/account",
        kind: "redirect",
        group: "personal.profile",
        canonicalRoute: "/settings/personal/profile",
        canonicalSection: null,
        redirectsTo: "/account/profile",
        titleKey: "CommonSidebar.accountSettings",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["avatar-menu", "command-palette"],
        aliasKey: null,
    },
    {
        id: "account.connections",
        currentRoute: "/account/connections",
        kind: "destination",
        group: "personal.connected-accounts",
        canonicalRoute: "/settings/personal/connected-accounts",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "AccountConnections.title",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [
                "connectedAccounts.google",
                "connectedAccounts.microsoft",
                "connectedCapture.google",
                "connectedCapture.microsoft",
            ],
            orgAdmin: false,
            manage: [],
            states: ["not-enabled", "ask-admin", "retry"],
        },
        entryPoints: ["account-tabs", "contextual"],
        aliasKey: null,
    },
    {
        id: "account.capture-reviews",
        currentRoute: "/account/connections/reviews",
        kind: "redirect",
        group: "personal.connected-accounts",
        canonicalRoute: "/settings/personal/connected-accounts",
        canonicalSection: "reviews",
        redirectsTo: "/account/connections",
        titleKey: "CommonSidebar.navCaptureReviews",
        access: {
            permissions: [],
            capabilities: ["connectedCapture.google", "connectedCapture.microsoft"],
            orgAdmin: false,
            manage: [],
            states: ["not-enabled", "retry"],
        },
        entryPoints: ["sidebar", "command-palette"],
        aliasKey: "Actions.keywords.navigate.captureReviews",
    },
    {
        id: "account.invites",
        currentRoute: "/account/invites",
        kind: "destination",
        group: "personal.workspaces",
        canonicalRoute: "/settings/personal/workspaces",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Account.tabInvites",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["account-tabs"],
        aliasKey: null,
    },
    {
        id: "account.notifications",
        currentRoute: "/account/notifications",
        kind: "destination",
        group: "personal.notifications",
        canonicalRoute: "/settings/personal/notifications",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Account.tabNotifications",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["account-tabs"],
        aliasKey: null,
    },
    {
        id: "account.profile",
        currentRoute: "/account/profile",
        kind: "destination",
        group: "personal.profile",
        canonicalRoute: "/settings/personal/profile",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Account.tabProfile",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["account-tabs"],
        aliasKey: null,
    },
    {
        id: "account.security",
        currentRoute: "/account/security",
        kind: "destination",
        group: "personal.security",
        canonicalRoute: "/settings/personal/security",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Account.tabSecurity",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["account-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.audit-log",
        currentRoute: "/admin/logs",
        kind: "destination",
        group: "workspace.audit-diagnostics",
        canonicalRoute: "/settings/workspace/audit-diagnostics",
        canonicalSection: "audit",
        redirectsTo: null,
        titleKey: "CommonSidebar.navAuditLog",
        access: {
            permissions: ["AUDIT_READ"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["sidebar", "command-palette"],
        aliasKey: null,
    },
    {
        id: "organization.home",
        currentRoute: "/organization",
        kind: "redirect",
        group: "organization.overview",
        canonicalRoute: "/settings/organization/overview",
        canonicalSection: null,
        redirectsTo: "/organization/overview",
        titleKey: null,
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "organization.ai",
        currentRoute: "/organization/ai",
        kind: "destination",
        group: "organization.ai-governance",
        canonicalRoute: "/settings/organization/ai-governance",
        canonicalSection: "ai-provider",
        redirectsTo: null,
        titleKey: "Organization.tabAi",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.allowed-domains",
        currentRoute: "/organization/allowed-domains",
        kind: "destination",
        group: "organization.identity",
        canonicalRoute: "/settings/organization/identity",
        canonicalSection: "allowed-domains",
        redirectsTo: null,
        titleKey: "Organization.tabDomains",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.audit",
        currentRoute: "/organization/audit",
        kind: "destination",
        group: "organization.audit-diagnostics",
        canonicalRoute: "/settings/organization/audit-diagnostics",
        canonicalSection: "audit",
        redirectsTo: null,
        titleKey: "Organization.tabAudit",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.data-requests",
        currentRoute: "/organization/data-requests",
        kind: "destination",
        group: "organization.data-requests",
        canonicalRoute: "/settings/organization/data-requests",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Organization.tabDataRequests",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.diagnostics",
        currentRoute: "/organization/diagnostics",
        kind: "destination",
        group: "organization.audit-diagnostics",
        canonicalRoute: "/settings/organization/audit-diagnostics",
        canonicalSection: "diagnostics",
        redirectsTo: null,
        titleKey: "Organization.tabDiagnostics",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.administrators",
        currentRoute: "/organization/members",
        kind: "destination",
        group: "organization.identity",
        canonicalRoute: "/settings/organization/identity",
        canonicalSection: "administrators",
        redirectsTo: null,
        titleKey: "Organization.tabMembers",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs", "sidebar", "command-palette"],
        aliasKey: null,
    },
    {
        id: "organization.overview",
        currentRoute: "/organization/overview",
        kind: "destination",
        group: "organization.overview",
        canonicalRoute: "/settings/organization/overview",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "Organization.tabOverview",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: true,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "organization.sso",
        currentRoute: "/organization/sso",
        kind: "destination",
        group: "organization.identity",
        canonicalRoute: "/settings/organization/identity",
        canonicalSection: "sso",
        redirectsTo: null,
        titleKey: "Organization.tabSso",
        access: {
            permissions: [],
            capabilities: ["sso"],
            orgAdmin: true,
            manage: [],
            states: ["not-enabled", "ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.approval-policies",
        currentRoute: "/records/approval-policies",
        kind: "destination",
        group: "workspace.crm",
        canonicalRoute: "/settings/workspace/crm",
        canonicalSection: "approval-policies",
        redirectsTo: null,
        titleKey: "CommonSidebar.navApprovalPolicies",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: false,
            manage: ["DOCUMENT_MANAGE"],
            states: [],
        },
        entryPoints: ["sidebar", "command-palette", "contextual"],
        aliasKey: null,
    },
    {
        id: "settings.home",
        currentRoute: "/settings",
        kind: "redirect",
        group: null,
        canonicalRoute: SETTINGS_HOME_ROUTE,
        canonicalSection: null,
        redirectsTo: "/settings/members",
        titleKey: "WorkspaceSettings.title",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "workspace.custom-fields",
        currentRoute: "/settings/custom-fields",
        kind: "destination",
        group: "workspace.crm",
        canonicalRoute: "/settings/workspace/crm",
        canonicalSection: "custom-fields",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabCustomFields",
        access: {
            permissions: ["CUSTOM_FIELD_MANAGE"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.data",
        currentRoute: "/settings/data",
        kind: "destination",
        group: "workspace.data-privacy",
        canonicalRoute: "/settings/workspace/data-privacy",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabData",
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.delivery",
        currentRoute: "/settings/delivery",
        kind: "destination",
        group: "workspace.communications",
        canonicalRoute: "/settings/workspace/communications",
        canonicalSection: "delivery",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabDelivery",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.diagnostics",
        currentRoute: "/settings/diagnostics",
        kind: "destination",
        group: "workspace.audit-diagnostics",
        canonicalRoute: "/settings/workspace/audit-diagnostics",
        canonicalSection: "diagnostics",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabDiagnostics",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs", "command-palette"],
        aliasKey: null,
    },
    {
        id: "workspace.email",
        currentRoute: "/settings/email",
        kind: "destination",
        group: "workspace.communications",
        canonicalRoute: "/settings/workspace/communications",
        canonicalSection: "email",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabEmail",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: ["mailManaged"],
            orgAdmin: false,
            manage: [],
            states: ["managed", "ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.general",
        currentRoute: "/settings/general",
        kind: "destination",
        group: "workspace.general",
        canonicalRoute: "/settings/workspace/general",
        canonicalSection: null,
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabGeneral",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.members",
        currentRoute: "/settings/members",
        kind: "destination",
        group: "workspace.people",
        canonicalRoute: "/settings/workspace/people",
        canonicalSection: "members",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabMembers",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: false,
            manage: ["MEMBER_MANAGE"],
            states: [],
        },
        entryPoints: ["settings-tabs", "sidebar", "command-palette", "contextual"],
        aliasKey: null,
    },
    {
        id: "legacy.settings-membership",
        currentRoute: "/settings/membership",
        kind: "redirect",
        group: "personal.workspaces",
        canonicalRoute: "/settings/personal/workspaces",
        canonicalSection: null,
        redirectsTo: "/account/invites",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "legacy.settings-notifications",
        currentRoute: "/settings/notifications",
        kind: "redirect",
        group: "personal.notifications",
        canonicalRoute: "/settings/personal/notifications",
        canonicalSection: null,
        redirectsTo: "/account/notifications",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "workspace.qualification",
        currentRoute: "/settings/qualification",
        kind: "destination",
        group: "workspace.crm",
        canonicalRoute: "/settings/workspace/crm",
        canonicalSection: "qualification",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabQualification",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "workspace.roles",
        currentRoute: "/settings/roles",
        kind: "destination",
        group: "workspace.people",
        canonicalRoute: "/settings/workspace/people",
        canonicalSection: "roles",
        redirectsTo: null,
        titleKey: "WorkspaceSettings.tabRoles",
        access: {
            permissions: ["ROLE_MANAGE"],
            capabilities: [],
            orgAdmin: false,
            manage: [],
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["settings-tabs"],
        aliasKey: null,
    },
    {
        id: "legacy.settings-rules",
        currentRoute: "/settings/rules",
        kind: "external-redirect",
        group: null,
        canonicalRoute: "/workflows",
        canonicalSection: null,
        redirectsTo: "/workflows",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "legacy.settings-security",
        currentRoute: "/settings/security",
        kind: "redirect",
        group: "personal.security",
        canonicalRoute: "/settings/personal/security",
        canonicalSection: null,
        redirectsTo: "/account/security",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "legacy.settings-sso",
        currentRoute: "/settings/sso",
        kind: "redirect",
        group: "organization.identity",
        canonicalRoute: "/settings/organization/identity",
        canonicalSection: "sso",
        redirectsTo: "/organization/sso",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "legacy.settings-workflow",
        currentRoute: "/settings/workflows/[legacyRuleId]",
        kind: "external-redirect",
        group: null,
        canonicalRoute: "/workflows/[workflowId]",
        canonicalSection: null,
        redirectsTo: "/workflows/[workflowId]",
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "workspace.people-directory",
        currentRoute: "/users",
        kind: "destination",
        group: "workspace.people",
        canonicalRoute: "/settings/workspace/people",
        canonicalSection: "directory",
        redirectsTo: null,
        titleKey: "CommonSidebar.navUsers",
        access: {
            permissions: [],
            capabilities: [],
            orgAdmin: false,
            manage: ["MEMBER_MANAGE"],
            states: [],
        },
        entryPoints: ["sidebar", "command-palette", "contextual"],
        aliasKey: null,
    },
    {
        id: "workspace.people-detail",
        currentRoute: "/users/[id]",
        kind: "destination",
        group: "workspace.people",
        canonicalRoute: "/settings/workspace/people",
        canonicalSection: "member-detail",
        redirectsTo: null,
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["contextual"],
        aliasKey: null,
    },
] as const satisfies readonly SettingsEntry[];

/** The app-directory roots whose every routed page must be registered in {@link SETTINGS_ENTRIES}. */
export const SETTINGS_ROUTE_ROOTS = ["account", "settings", "organization", "users", "admin"] as const;
