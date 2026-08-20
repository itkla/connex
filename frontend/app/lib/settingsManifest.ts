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
export type SettingsCapabilityKey = NonNullable<
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
      }[keyof InstanceCapabilities]
>;

/** A capability the destination depends on, and the value that capability must hold. */
export type SettingsCapabilityRequirement = {
    key: SettingsCapabilityKey;
    /** The value the capability must hold for the destination to exist. */
    expected: boolean;
};

/**
 * A capability state under which a rendering page forwards the browser elsewhere instead of
 * explaining itself in place. #1340 forbids exactly this teleport, and no entry declares one any
 * more: `/settings/email` and `/organization/sso` were the complete set the manifest recorded, and
 * both now render a {@link SettingsAvailabilityState} where they stand.
 *
 * The shape survives its last two occupants on purpose. It is what a future forward would have to
 * be declared as, and the gates over it in `settingsManifest.test.ts` are what would catch one —
 * they hold at zero forwards today and fail the moment a page starts teleporting again.
 */
export type SettingsConditionalForward = {
    capability: SettingsCapabilityKey;
    /** The capability value that triggers the forward. */
    expected: boolean;
    /** Where the shipped page sends the browser under that state. */
    to: string;
};

/**
 * What a destination requires, split by the rule that drives navigation visibility:
 *
 * - **visibility** (`permissions`, `capabilities`, `orgAdmin`) — the shipped page refuses to render
 *   its content without it, whether it refuses in the browser (an access-denied panel) or because
 *   the read its content needs is gated server-side. A navigation consumer may hide or explain the
 *   destination on these.
 * - **manage** (`manage`, `orgWrite`) — the page renders and only its mutations are gated. A
 *   consumer must NOT hide the destination on these; doing so would hide a page that works today.
 *
 * The same permission legitimately lands in both buckets across different pages:
 * `WORKSPACE_SETTINGS` gates the delivery and mail reads that `/settings/delivery` and
 * `/settings/email` need, but on `/settings/members` it gates only the allowed-domains block inside
 * a page that otherwise renders for any member.
 */
export type SettingsAccess = {
    /** Backend `Permission` constants required to render the destination's content. */
    permissions: readonly string[];
    /** Capability requirements the destination's content depends on. */
    capabilities: readonly SettingsCapabilityRequirement[];
    /** Whether every capability requirement must hold, or any one of them. */
    capabilityMatch: "all" | "any";
    /** Whether the viewer must hold an organization role to reach the destination. */
    orgAdmin: boolean;
    /** Backend `Permission` constants that gate the destination's writes only. */
    manage: readonly string[];
    /** The organization role the destination's writes require, or null when it has none. */
    orgWrite: "owner" | "admin" | null;
    /**
     * The in-place states this destination must be able to explain about itself once #1340 lands.
     * Partly shipped: no destination forwards away from its own capability state any more, and the
     * navigation keeps a state-declaring destination visible when its capability resolves against
     * it. Several destinations still answer a refused permission with the shared access-denied
     * state rather than the `ask-admin` posture, which the scope-group PRs adopt as they move.
     */
    states: readonly SettingsAvailabilityState[];
};

/**
 * A job #1340 makes addressable that no route served before.
 *
 * `SETTINGS_ENTRIES` can only describe destinations that exist today, so a job the epic requires but
 * that ships as a block inside another page has nowhere to be recorded — #1398 enumerated three of
 * them (workspace allowed domains, workspace notification defaults, workflow configuration) as
 * route gaps. A group declares them here, so the section a consolidation *creates* is navigable and
 * searchable on the same terms as the sections it absorbs.
 */
export type SettingsGroupSection = {
    /** The section slug, giving the job its deep link at `{group.route}#{slug}`. */
    slug: string;
    /** The shipped message key that labels the section. */
    titleKey: string;
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
    /**
     * The route gaps this group's canonical destination fills — jobs it makes addressable that no
     * entry can describe because no route ever served them. Empty for a group that only absorbs
     * existing destinations.
     */
    gapSections?: readonly SettingsGroupSection[];
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
    /** Query keys the forward carries and a successor redirect must preserve. */
    redirectQuery: readonly string[];
    /** A capability state under which a rendering destination forwards away instead; null when none. */
    conditionalForward: SettingsConditionalForward | null;
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
    capabilityMatch: "all",
    orgAdmin: false,
    manage: [],
    orgWrite: null,
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
        titleKey: "SettingsNav.groupNotificationPreferences",
        epicName: "Notification preferences",
    },
    {
        id: "personal.workspaces",
        scope: "personal",
        route: "/settings/personal/workspaces",
        order: 5,
        titleKey: "SettingsNav.groupWorkspacesInvitations",
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
        titleKey: "SettingsNav.groupPeopleAccess",
        epicName: "People & access",
        gapSections: [{ slug: "allowed-domains", titleKey: "WorkspaceMembers.domainsTitle" }],
    },
    {
        id: "workspace.crm",
        scope: "workspace",
        route: "/settings/workspace/crm",
        order: 3,
        titleKey: "SettingsNav.groupCrmConfiguration",
        epicName: "CRM configuration",
    },
    {
        id: "workspace.communications",
        scope: "workspace",
        route: "/settings/workspace/communications",
        order: 4,
        titleKey: "SettingsNav.groupCommunications",
        epicName: "Communications",
    },
    {
        id: "workspace.data-privacy",
        scope: "workspace",
        route: "/settings/workspace/data-privacy",
        order: 5,
        titleKey: "SettingsNav.groupDataPrivacy",
        epicName: "Data & privacy",
    },
    {
        id: "workspace.audit-diagnostics",
        scope: "workspace",
        route: "/settings/workspace/audit-diagnostics",
        order: 6,
        titleKey: "SettingsNav.groupAuditDiagnostics",
        epicName: "Audit & diagnostics",
    },
    {
        id: "organization.general",
        scope: "organization",
        route: "/settings/organization/general",
        order: 1,
        titleKey: "SettingsNav.groupOrganizationGeneral",
        epicName: "General",
    },
    {
        id: "organization.identity",
        scope: "organization",
        route: "/settings/organization/identity",
        order: 2,
        titleKey: "SettingsNav.groupIdentityAdministrators",
        epicName: "Identity & administrators",
    },
    {
        id: "organization.ai-governance",
        scope: "organization",
        route: "/settings/organization/ai-governance",
        order: 3,
        titleKey: "SettingsNav.groupAiDataGovernance",
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
        titleKey: "SettingsNav.groupAuditDiagnostics",
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "AccountConnections.title",
        access: {
            permissions: [],
            capabilities: [
                { key: "connectedAccounts.google", expected: true },
                { key: "connectedAccounts.microsoft", expected: true },
                { key: "connectedCapture.google", expected: true },
                { key: "connectedCapture.microsoft", expected: true },
            ],
            capabilityMatch: "any",
            orgAdmin: false,
            manage: ["WORKSPACE_SETTINGS"],
            orgWrite: null,
            states: ["not-enabled", "retry"],
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
        redirectQuery: ["provider", "panel"],
        conditionalForward: null,
        titleKey: "CommonSidebar.navCaptureReviews",
        access: {
            permissions: [],
            capabilities: [
                { key: "connectedCapture.google", expected: true },
                { key: "connectedCapture.microsoft", expected: true },
            ],
            capabilityMatch: "any",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "CommonSidebar.navAuditLog",
        access: {
            permissions: ["AUDIT_READ"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["sidebar", "command-palette"],
        aliasKey: null,
    },
    {
        id: "organization.home",
        currentRoute: "/organization",
        kind: "redirect",
        group: "organization.general",
        canonicalRoute: "/settings/organization/general",
        canonicalSection: null,
        redirectsTo: "/organization/overview",
        redirectQuery: [],
        conditionalForward: null,
        titleKey: null,
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabAi",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "admin",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabDomains",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "admin",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabAudit",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabDataRequests",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "admin",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabDiagnostics",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabMembers",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "owner",
            states: ["ask-admin", "retry"],
        },
        entryPoints: ["organization-tabs", "sidebar", "command-palette"],
        aliasKey: null,
    },
    {
        id: "organization.overview",
        currentRoute: "/organization/overview",
        kind: "destination",
        group: "organization.general",
        canonicalRoute: "/settings/organization/general",
        canonicalSection: null,
        redirectsTo: null,
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabOverview",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "admin",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "Organization.tabSso",
        access: {
            permissions: [],
            capabilities: [{ key: "sso", expected: true }],
            capabilityMatch: "all",
            orgAdmin: true,
            manage: [],
            orgWrite: "admin",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "CommonSidebar.navApprovalPolicies",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: ["DOCUMENT_MANAGE"],
            orgWrite: null,
            states: [],
        },
        entryPoints: ["sidebar", "command-palette", "contextual"],
        aliasKey: null,
    },
    {
        id: "settings.home",
        currentRoute: "/settings",
        kind: "destination",
        group: null,
        canonicalRoute: SETTINGS_HOME_ROUTE,
        canonicalSection: null,
        redirectsTo: null,
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "SettingsHome.title",
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabCustomFields",
        access: {
            permissions: ["CUSTOM_FIELD_MANAGE"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabData",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [
                "ACTIVITY_CREATE",
                "COMPANY_CREATE",
                "CUSTOM_FIELD_MANAGE",
                "DEAL_CREATE",
                "NOTE_CREATE",
                "PERSON_CREATE",
                "TAG_MANAGE",
                "TASK_CREATE",
            ],
            orgWrite: null,
            states: [],
        },
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabDelivery",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabDiagnostics",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabEmail",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [{ key: "mailManaged", expected: false }],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabGeneral",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabMembers",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: ["MEMBER_MANAGE", "WORKSPACE_SETTINGS"],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabQualification",
        access: {
            permissions: ["WORKSPACE_SETTINGS"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "WorkspaceSettings.tabRoles",
        access: {
            permissions: ["ROLE_MANAGE"],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: [],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: [],
        aliasKey: null,
    },
    {
        id: "workspace.people",
        currentRoute: "/settings/workspace/people",
        kind: "destination",
        group: "workspace.people",
        canonicalRoute: "/settings/workspace/people",
        canonicalSection: null,
        redirectsTo: null,
        redirectQuery: [],
        conditionalForward: null,
        titleKey: "SettingsNav.groupPeopleAccess",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: ["MEMBER_MANAGE", "ROLE_MANAGE", "WORKSPACE_SETTINGS"],
            orgWrite: null,
            states: [],
        },
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
        redirectQuery: [],
        conditionalForward: null,
        /**
         * The consolidated name, not the shipped one. `CommonSidebar.navUsers` renders "Users" /
         * 「ユーザー」, which PRODUCT.md §4 bans for a person in a workspace, and the banned-terms gate
         * cannot catch it there because `user` is unclassifiable in the general case. This key is
         * what the navigation and settings search show, and both now lead to the section of People &
         * access that owns this job, which is named for members. The standalone `/users` page keeps
         * its shipped label until its redirect lands.
         */
        titleKey: "SettingsPeople.directoryTitle",
        access: {
            permissions: [],
            capabilities: [],
            capabilityMatch: "all",
            orgAdmin: false,
            manage: ["MEMBER_MANAGE"],
            orgWrite: null,
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
        redirectQuery: [],
        conditionalForward: null,
        titleKey: null,
        access: NO_ACCESS_REQUIREMENTS,
        entryPoints: ["contextual"],
        aliasKey: null,
    },
] as const satisfies readonly SettingsEntry[];

/** The app-directory roots whose every routed page must be registered in {@link SETTINGS_ENTRIES}. */
export const SETTINGS_ROUTE_ROOTS = ["account", "settings", "organization", "users", "admin"] as const;
