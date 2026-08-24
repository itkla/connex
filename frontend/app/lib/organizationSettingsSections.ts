import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/**
 * The addressable sections of the four consolidated organization destinations of #1340 PR 6, and
 * the one that has none.
 *
 * The workspace scope groups each got a module of their own, one per PR. Five more copies of the
 * same forty lines would be five places for the manifest-derivation to drift, so the organization
 * scope states its sections once and derives every group's manifest side through one helper. Each
 * group still exports its own route, its own ordered slugs, and its own href builder, which is what
 * the pages and the gate over them consume.
 *
 * The declared order is each page's reading order. The manifest side is unordered by construction —
 * it is whatever the manifest files under the group, absorbed sections and declared route gaps
 * alike — so the gate compares the two as sets and a slug renamed or retired there fails here
 * rather than leaving a deep link pointing at nothing.
 */

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

/**
 * The sections the manifest files under a group: what it absorbed, then what it makes addressable.
 *
 * Kind is deliberately not filtered. An absorbed job is a section of this destination whether its
 * old address still renders or now forwards here, and after #1340 PR 8 most of them forward —
 * filtering would drop five organization sections out of every page that derives its anchors from
 * this list, in the same commit that finished consolidating them.
 */
function manifestSections(groupId: string, route: string): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (
            entry.group === groupId
            && entry.canonicalRoute === route
            && entry.canonicalSection !== null
        ) {
            if (!sections.includes(entry.canonicalSection)) sections.push(entry.canonicalSection);
        }
    }
    const group = MANIFEST_GROUPS.find((candidate) => candidate.id === groupId);
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

/** The canonical route of the organization's General destination. */
export const ORGANIZATION_GENERAL_ROUTE = "/settings/organization/general";

/**
 * General's sections, in reading order: what the organization is called, how its workspaces and
 * authority fit together, and how either of those ends.
 */
export const ORGANIZATION_GENERAL_SECTIONS = ["identity", "layout", "lifecycle"] as const;

/** One addressable section of the organization's General destination. */
export type OrganizationGeneralSection = (typeof ORGANIZATION_GENERAL_SECTIONS)[number];

export const MANIFEST_ORGANIZATION_GENERAL_SECTIONS: readonly string[] = manifestSections(
    "organization.general",
    ORGANIZATION_GENERAL_ROUTE,
);

/**
 * The deep link to one section of the organization's General destination.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function organizationGeneralSectionHref(section: OrganizationGeneralSection): string {
    return `${ORGANIZATION_GENERAL_ROUTE}#${section}`;
}

/** The canonical route of Identity & administrators. */
export const ORGANIZATION_IDENTITY_ROUTE = "/settings/organization/identity";

/**
 * Identity & administrators' sections, in reading order: who runs the organization, who may be
 * invited into it, and how they sign in.
 */
export const ORGANIZATION_IDENTITY_SECTIONS = ["administrators", "allowed-domains", "sso"] as const;

/** One addressable section of Identity & administrators. */
export type OrganizationIdentitySection = (typeof ORGANIZATION_IDENTITY_SECTIONS)[number];

export const MANIFEST_ORGANIZATION_IDENTITY_SECTIONS: readonly string[] = manifestSections(
    "organization.identity",
    ORGANIZATION_IDENTITY_ROUTE,
);

/**
 * The deep link to one section of Identity & administrators.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function organizationIdentitySectionHref(section: OrganizationIdentitySection): string {
    return `${ORGANIZATION_IDENTITY_ROUTE}#${section}`;
}

/** The canonical route of AI & data governance. */
export const ORGANIZATION_AI_GOVERNANCE_ROUTE = "/settings/organization/ai-governance";

/** AI & data governance's sections: the provider the organization brings, and the limits on it. */
export const ORGANIZATION_AI_GOVERNANCE_SECTIONS = ["ai-provider"] as const;

/** One addressable section of AI & data governance. */
export type OrganizationAiGovernanceSection = (typeof ORGANIZATION_AI_GOVERNANCE_SECTIONS)[number];

export const MANIFEST_ORGANIZATION_AI_GOVERNANCE_SECTIONS: readonly string[] = manifestSections(
    "organization.ai-governance",
    ORGANIZATION_AI_GOVERNANCE_ROUTE,
);

/**
 * The deep link to one section of AI & data governance.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function organizationAiGovernanceSectionHref(
    section: OrganizationAiGovernanceSection,
): string {
    return `${ORGANIZATION_AI_GOVERNANCE_ROUTE}#${section}`;
}

/** The canonical route of the organization's Data requests destination. */
export const ORGANIZATION_DATA_REQUESTS_ROUTE = "/settings/organization/data-requests";

/** Data requests' sections: the requests themselves, and the disclosures assembled for them. */
export const ORGANIZATION_DATA_REQUESTS_SECTIONS = ["requests"] as const;

/** One addressable section of the organization's Data requests destination. */
export type OrganizationDataRequestsSection = (typeof ORGANIZATION_DATA_REQUESTS_SECTIONS)[number];

export const MANIFEST_ORGANIZATION_DATA_REQUESTS_SECTIONS: readonly string[] = manifestSections(
    "organization.data-requests",
    ORGANIZATION_DATA_REQUESTS_ROUTE,
);

/**
 * The deep link to one section of the organization's Data requests destination.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function organizationDataRequestsSectionHref(
    section: OrganizationDataRequestsSection,
): string {
    return `${ORGANIZATION_DATA_REQUESTS_ROUTE}#${section}`;
}

/** The canonical route of the organization's Audit & diagnostics destination. */
export const ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE = "/settings/organization/audit-diagnostics";

/**
 * The organization's Audit & diagnostics sections, in the group's own reading order: what happened
 * above any single workspace, then whether the machinery behind it is healthy.
 */
export const ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS = ["audit", "diagnostics"] as const;

/** One addressable section of the organization's Audit & diagnostics destination. */
export type OrganizationAuditDiagnosticsSection =
    (typeof ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS)[number];

export const MANIFEST_ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS: readonly string[] = manifestSections(
    "organization.audit-diagnostics",
    ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE,
);

/**
 * The deep link to one section of the organization's Audit & diagnostics destination.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function organizationAuditDiagnosticsSectionHref(
    section: OrganizationAuditDiagnosticsSection,
): string {
    return `${ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE}#${section}`;
}
