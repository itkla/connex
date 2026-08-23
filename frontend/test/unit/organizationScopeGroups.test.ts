import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    MANIFEST_ORGANIZATION_AI_GOVERNANCE_SECTIONS,
    MANIFEST_ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS,
    MANIFEST_ORGANIZATION_DATA_REQUESTS_SECTIONS,
    MANIFEST_ORGANIZATION_GENERAL_SECTIONS,
    MANIFEST_ORGANIZATION_IDENTITY_SECTIONS,
    ORGANIZATION_AI_GOVERNANCE_ROUTE,
    ORGANIZATION_AI_GOVERNANCE_SECTIONS,
    ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE,
    ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS,
    ORGANIZATION_DATA_REQUESTS_ROUTE,
    ORGANIZATION_DATA_REQUESTS_SECTIONS,
    ORGANIZATION_GENERAL_ROUTE,
    ORGANIZATION_GENERAL_SECTIONS,
    ORGANIZATION_IDENTITY_ROUTE,
    ORGANIZATION_IDENTITY_SECTIONS,
    organizationAiGovernanceSectionHref,
    organizationAuditDiagnosticsSectionHref,
    organizationDataRequestsSectionHref,
    organizationGeneralSectionHref,
    organizationIdentitySectionHref,
} from "@/app/lib/organizationSettingsSections";
import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsEntry,
} from "@/app/lib/settingsManifest";
import {
    entryVisible,
    resolveSettingsNavigation,
    sectionVisible,
    type SettingsNavViewer,
} from "@/app/lib/settingsNavigation";
import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * Gate over the five consolidated organization destinations of #1340 PR 6 — General, Identity &
 * administrators, AI & data governance, Data requests, and the organization's Audit & diagnostics.
 *
 * The epic's acceptance for this workstream is that eight organization tabs resolve to five pages
 * whose sections each keep a stable deep link, that organization standing still decides who reaches
 * any of them, that the owner-only writes inside the administrator roster stay owner-only, and that
 * the banned page name the 2026-08-19 ruling retired does not follow the content to its new home.
 * This suite holds the parts of that a browser pass cannot: which anchors each page owns, that they
 * are spelled once and reached through a shared builder, that the scope gate is load-bearing rather
 * than incidental, and that every legacy route still renders exactly what it did.
 *
 * What it deliberately does not assert: how the sections look, whether an arrival lands where the
 * reader expected, and whether the organization roster behaves for a real owner against a real
 * backend. Those are the browser pass's job.
 */
const APP = path.join(process.cwd(), "app");
const COMPONENTS = path.join(APP, "components", "settings");
const ORG_COMPONENTS = path.join(APP, "components", "organization");
const GENERAL_VIEW = path.join(COMPONENTS, "OrganizationGeneral.tsx");
const IDENTITY_VIEW = path.join(COMPONENTS, "OrganizationIdentity.tsx");
const AI_VIEW = path.join(COMPONENTS, "OrganizationAiGovernance.tsx");
const DATA_REQUESTS_VIEW = path.join(COMPONENTS, "OrganizationDataRequests.tsx");
const AUDIT_VIEW = path.join(COMPONENTS, "OrganizationAuditDiagnostics.tsx");
const OVERVIEW_PANEL = path.join(ORG_COMPONENTS, "OrganizationOverviewPanel.tsx");
const MEMBERS_PANEL = path.join(ORG_COMPONENTS, "OrgMembersPanel.tsx");
const DOMAINS_PANEL = path.join(ORG_COMPONENTS, "OrgAllowedDomainsPanel.tsx");
const SETTINGS_LAYOUT = path.join(APP, "(app)", "settings", "organization", "layout.tsx");
const LEGACY_LAYOUT = path.join(APP, "(app)", "organization", "layout.tsx");

function source(file: string): string {
    return readFileSync(file, "utf8");
}

function routeDir(route: string): string {
    return path.join(APP, "(app)", ...route.split("/").filter(Boolean));
}

/** Every `.ts`/`.tsx` file under `app/`, for the one-spelling scan. */
function appFiles(directory: string): string[] {
    const files: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            files.push(...appFiles(full));
            continue;
        }
        if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) files.push(full);
    }
    return files;
}

type ScopeGroup = {
    id: string;
    route: string;
    sections: readonly string[];
    manifestSections: readonly string[];
    href: (section: never) => string;
    view: string;
    /**
     * Where the section anchors are drawn. Usually the page's own view; for General it is the panel,
     * which owns the three headings its deep links name and therefore the regions around them.
     */
    anchors?: string;
    titleKey: string;
};

const GROUPS: readonly ScopeGroup[] = [
    {
        id: "organization.general",
        route: ORGANIZATION_GENERAL_ROUTE,
        sections: ORGANIZATION_GENERAL_SECTIONS,
        manifestSections: MANIFEST_ORGANIZATION_GENERAL_SECTIONS,
        href: organizationGeneralSectionHref as (section: never) => string,
        view: GENERAL_VIEW,
        anchors: OVERVIEW_PANEL,
        titleKey: "SettingsNav.groupOrganizationGeneral",
    },
    {
        id: "organization.identity",
        route: ORGANIZATION_IDENTITY_ROUTE,
        sections: ORGANIZATION_IDENTITY_SECTIONS,
        manifestSections: MANIFEST_ORGANIZATION_IDENTITY_SECTIONS,
        href: organizationIdentitySectionHref as (section: never) => string,
        view: IDENTITY_VIEW,
        titleKey: "SettingsNav.groupIdentityAdministrators",
    },
    {
        id: "organization.ai-governance",
        route: ORGANIZATION_AI_GOVERNANCE_ROUTE,
        sections: ORGANIZATION_AI_GOVERNANCE_SECTIONS,
        manifestSections: MANIFEST_ORGANIZATION_AI_GOVERNANCE_SECTIONS,
        href: organizationAiGovernanceSectionHref as (section: never) => string,
        view: AI_VIEW,
        titleKey: "SettingsNav.groupAiDataGovernance",
    },
    {
        id: "organization.data-requests",
        route: ORGANIZATION_DATA_REQUESTS_ROUTE,
        sections: ORGANIZATION_DATA_REQUESTS_SECTIONS,
        manifestSections: MANIFEST_ORGANIZATION_DATA_REQUESTS_SECTIONS,
        href: organizationDataRequestsSectionHref as (section: never) => string,
        view: DATA_REQUESTS_VIEW,
        titleKey: "Organization.tabDataRequests",
    },
    {
        id: "organization.audit-diagnostics",
        route: ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE,
        sections: ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS,
        manifestSections: MANIFEST_ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS,
        href: organizationAuditDiagnosticsSectionHref as (section: never) => string,
        view: AUDIT_VIEW,
        titleKey: "SettingsNav.groupAuditDiagnostics",
    },
];

describe("each organization destination owns the sections its manifest group promises", () => {
    it.each(GROUPS)("$id serves the canonical route the manifest gives it", (group) => {
        const manifestGroup = SETTINGS_GROUPS.find((candidate) => candidate.id === group.id);
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.currentRoute === group.route);

        expect(manifestGroup?.route).toBe(group.route);
        expect(existsSync(path.join(routeDir(group.route), "page.tsx"))).toBe(true);
        expect(entry, "the served route is a destination the manifest describes").toBeDefined();
        expect(entry?.group).toBe(group.id);
        expect(
            entry?.canonicalRoute,
            "a destination that owns its group's route is its own canonical owner",
        ).toBe(group.route);
        expect(entry?.canonicalSection).toBeNull();
        expect(entry?.titleKey).toBe(group.titleKey);
    });

    it.each(GROUPS)("$id holds exactly the sections the manifest declares", (group) => {
        expect(
            [...group.sections].sort(),
            "absorbed sections and declared route gaps alike, and nothing invented beside them",
        ).toEqual([...group.manifestSections].sort());
    });

    it.each(GROUPS)("$id gives each section a real element to arrive at", (group) => {
        const view = source(group.anchors ?? group.view);
        const anchored = group.sections.filter(
            (section) => view.includes(`section="${section}"`) || view.includes(`id="${section}"`),
        );

        expect(anchored).toEqual([...group.sections]);
    });

    it.each(GROUPS)("$id registers every section with the arrival hook", (group) => {
        expect(
            source(group.view),
            "a page that does not register cannot be arrived at by fragment",
        ).toContain("useSectionArrival(");
    });

    it("lets the overview panel register the three blocks it owns the headings for", () => {
        const view = source(GENERAL_VIEW);
        const panel = source(OVERVIEW_PANEL);

        expect(
            view,
            "General hands the registrar down, because the panel — not the page — draws the headings its deep links name",
        ).toContain("<OrganizationOverviewPanel sections={sections} />");
        for (const section of ORGANIZATION_GENERAL_SECTIONS) {
            expect(panel).toContain(`section="${section}"`);
        }
        expect(
            panel,
            "and the legacy route, which passes no registrar, keeps rendering the blocks bare",
        ).toContain("if (!sections) return children;");
    });

    it("builds every deep link from one place, so an anchor cannot be spelled two ways", () => {
        expect(organizationGeneralSectionHref("lifecycle")).toBe(
            `${ORGANIZATION_GENERAL_ROUTE}#lifecycle`,
        );
        expect(organizationIdentitySectionHref("sso")).toBe(`${ORGANIZATION_IDENTITY_ROUTE}#sso`);
        expect(organizationAiGovernanceSectionHref("ai-provider")).toBe(
            `${ORGANIZATION_AI_GOVERNANCE_ROUTE}#ai-provider`,
        );
        expect(organizationDataRequestsSectionHref("requests")).toBe(
            `${ORGANIZATION_DATA_REQUESTS_ROUTE}#requests`,
        );
        expect(organizationAuditDiagnosticsSectionHref("diagnostics")).toBe(
            `${ORGANIZATION_AUDIT_DIAGNOSTICS_ROUTE}#diagnostics`,
        );

        const builder = path.join(APP, "lib", "organizationSettingsSections.ts");
        const strays = appFiles(APP)
            .filter((file) => file !== builder)
            .filter((file) => {
                const text = source(file);
                return GROUPS.some((group) => text.includes(`${group.route}#`));
            });

        expect(
            strays.map((file) => path.relative(process.cwd(), file)),
            "route an organization deep link through its section href builder rather than writing the fragment out",
        ).toEqual([]);
    });
});

const ALL_CAPABILITIES: InstanceCapabilities = {
    sso: true,
    socialLogin: { google: true, microsoft: true },
    connectedAccounts: { google: true, microsoft: true },
    connectedCapture: { google: true, microsoft: true },
    mailManaged: false,
    businessCardScanning: true,
    businessCardImport: true,
    campaignDelivery: true,
};

function viewer(overrides: Partial<SettingsNavViewer> = {}): SettingsNavViewer {
    return {
        capabilities: ALL_CAPABILITIES,
        permissions: new Set(SETTINGS_ENTRIES.flatMap((entry) => [...entry.access.permissions])),
        isOrgAdmin: true,
        ...overrides,
    };
}

const organizationEntries: readonly SettingsEntry[] = SETTINGS_ENTRIES.filter(
    (entry) => entry.access.orgAdmin,
);

describe("organization standing decides who reaches the organization destinations", () => {
    it("declares the scope gate on every destination and every section it absorbed", () => {
        const grouped = SETTINGS_ENTRIES.filter(
            (entry) =>
                entry.group?.startsWith("organization.") === true
                && entry.kind === "destination",
        );

        expect(grouped.length).toBeGreaterThan(5);
        expect(
            grouped.filter((entry) => !entry.access.orgAdmin).map((entry) => entry.id),
            "an organization job that does not declare the scope gate is one the navigation would offer to any member",
        ).toEqual([]);
        expect(
            grouped.filter((entry) => !entry.access.states.includes("ask-admin")).map((e) => e.id),
            "a refusal has to name who can lift it",
        ).toEqual([]);
    });

    it("re-establishes that gate on the route, because the settings shell does not carry it", () => {
        const layout = source(SETTINGS_LAYOUT);

        expect(
            layout,
            "these routes are under /settings, whose layout knows only about the workspace",
        ).toContain("activeWorkspace.orgRole === null");
        expect(
            source(LEGACY_LAYOUT),
            "and the legacy layout keeps the same gate, from the same source, unchanged",
        ).toContain("const isOrgAdmin = activeWorkspace.orgRole !== null;");
        expect(layout).toContain('state="ask-admin"');
        expect(
            layout,
            "standing resolved for one workspace must not keep rendering after a switch out of the organization",
        ).toContain("<OrganizationWorkspaceGuard");
    });

    it("drops the organization scope, its groups, and its section names for a reader with no role", () => {
        const outsider = viewer({ isOrgAdmin: false });
        const model = resolveSettingsNavigation({
            viewer: outsider,
            translate: (key) => key,
            scopeNames: { personal: "P", workspace: "W", organization: "O" },
            workspaceName: "Northstar",
            organizationName: "Klae",
        });

        expect(model.map((scope) => scope.scope)).toEqual(["personal", "workspace"]);
        expect(
            organizationEntries.filter((entry) => entryVisible(entry, outsider)).map((e) => e.id),
            "no organization destination is offered",
        ).toEqual([]);
        expect(
            organizationEntries.filter((entry) => sectionVisible(entry, outsider)).map((e) => e.id),
            "and no organization section is named either — a name is as much of a disclosure as a row",
        ).toEqual([]);
    });

    it("catches the leak the gate exists to prevent", () => {
        const identity = SETTINGS_ENTRIES.find((entry) => entry.id === "organization.identity");
        if (!identity) throw new Error("organization.identity left the manifest");
        const administrators = SETTINGS_ENTRIES.find(
            (entry) => entry.id === "organization.administrators",
        );
        if (!administrators) throw new Error("organization.administrators left the manifest");
        const outsider = viewer({ isOrgAdmin: false });
        const ungated = (entry: SettingsEntry): SettingsEntry => ({
            ...entry,
            access: { ...entry.access, orgAdmin: false },
        });

        expect(entryVisible(identity, outsider)).toBe(false);
        expect(sectionVisible(administrators, outsider)).toBe(false);
        expect(
            entryVisible(ungated(identity), outsider),
            "dropping the scope gate is what would offer the destination to any member; the predicates catch nothing else about it",
        ).toBe(true);
        expect(
            sectionVisible(ungated(administrators), outsider),
            "and would put the administrator roster's name into a non-administrator's settings search",
        ).toBe(true);
    });

    it("keeps every organization destination for an administrator", () => {
        const admin = viewer();

        expect(
            organizationEntries.filter((entry) => !sectionVisible(entry, admin)).map((e) => e.id),
            "nothing an administrator may read disappears from the navigation",
        ).toEqual([]);
    });
});

describe("the organization destinations keep the write boundaries their panels enforce", () => {
    it("records the roster as owner-only and the rest of Identity as administrator work", () => {
        const administrators = SETTINGS_ENTRIES.find(
            (entry) => entry.id === "organization.administrators",
        );
        const domains = SETTINGS_ENTRIES.find(
            (entry) => entry.id === "organization.allowed-domains",
        );
        const sso = SETTINGS_ENTRIES.find((entry) => entry.id === "organization.sso");
        const destination = SETTINGS_ENTRIES.find((entry) => entry.id === "organization.identity");

        expect(administrators?.access.orgWrite).toBe("owner");
        expect(domains?.access.orgWrite).toBe("admin");
        expect(sso?.access.orgWrite).toBe("admin");
        expect(
            destination?.access.orgWrite,
            "the destination admits administrator writes; the section that does not says so where it stands",
        ).toBe("admin");
    });

    it("renders no roster mutation an organization administrator would be refused", () => {
        const panel = source(MEMBERS_PANEL);

        expect(panel).toContain('const isOwner = activeWorkspace?.orgRole === "owner";');
        expect(
            panel,
            "changing a role is owner-only on the backend, so a non-owner reads a badge instead",
        ).toContain("const editable = isOwner && !lockedSoleOwner;");
        expect(
            panel,
            "so is removing one, so the row menu is not drawn at all",
        ).toContain("const removable = isOwner && !lockedSoleOwner;");
        expect(
            panel,
            "and so is adding one: §6 prefers no entry point over a locked door",
        ).toContain("{isOwner && (");
    });

    it("leaves the administrator-writable sections ungated on the owner role", () => {
        const domains = source(DOMAINS_PANEL);

        expect(
            domains.includes("orgRole"),
            "gating the domain policy on ownership would hide a control the backend grants any administrator",
        ).toBe(false);
    });
});

describe("the retired page name does not follow the content to its new home", () => {
    it("leaves Organization.tabOverview on the legacy tab strip alone", () => {
        const named = [
            ...SETTINGS_ENTRIES.map((entry) => entry.titleKey),
            ...SETTINGS_GROUPS.map((group) => group.titleKey),
        ].filter((key): key is string => key !== null);

        expect(
            named.filter((key) => key === "Organization.tabOverview"),
            "§7 retires Overview as a page name and the 2026-08-19 ruling names this group General, so nothing the settings navigation renders may resolve to it",
        ).toEqual([]);
        expect(
            source(path.join(ORG_COMPONENTS, "OrgTabs.tsx")),
            "the legacy tab strip still titles the route it still serves",
        ).toContain("tabOverview");
    });

    it("names each destination by the group name the epic gives it", () => {
        for (const group of GROUPS) {
            const manifestGroup = SETTINGS_GROUPS.find((candidate) => candidate.id === group.id);
            expect(manifestGroup?.epicName).toBeTruthy();
            expect(manifestGroup?.epicName).not.toBe("Overview");
        }
    });
});

describe("the organization destinations' copy resolves to finished sentences", () => {
    const NAMESPACES = [
        "SettingsOrgGeneral",
        "SettingsOrgIdentity",
        "SettingsOrgAi",
        "SettingsOrgDataRequests",
        "SettingsOrgAuditDiagnostics",
    ];
    /** The only keys these pages render with arguments; everything else must stand on its own. */
    const INTERPOLATED = new Set(["description"]);

    it.each(["en", "ja"])("leaves no unfilled placeholder in the %s catalog", (locale) => {
        const catalog = JSON.parse(
            readFileSync(path.join(process.cwd(), "messages", locale, "settings.json"), "utf8"),
        ) as Record<string, Record<string, string>>;

        const unfilled: string[] = [];
        for (const namespace of NAMESPACES) {
            expect(catalog[namespace], `${namespace} must exist in ${locale}`).toBeDefined();
            for (const [key, value] of Object.entries(catalog[namespace] ?? {})) {
                if (INTERPOLATED.has(key)) continue;
                if (/\{[a-zA-Z]/.test(value)) unfilled.push(`${namespace}.${key}`);
            }
        }

        expect(
            unfilled,
            "a string with a placeholder that its call site passes no argument for renders the braces to the reader",
        ).toEqual([]);
    });

    it("passes the organization name to every string that asks for one", () => {
        for (const group of GROUPS) {
            expect(source(group.view)).toContain(
                'organization: activeWorkspace?.orgName ?? ""',
            );
        }
    });
});

describe("the legacy organization routes keep rendering what they always did", () => {
    it.each([
        "/organization/overview",
        "/organization/members",
        "/organization/allowed-domains",
        "/organization/sso",
        "/organization/ai",
        "/organization/data-requests",
        "/organization/audit",
        "/organization/diagnostics",
    ])("still serves %s from the manifest and from disk", (route) => {
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.currentRoute === route);

        expect(entry?.kind, "the redirects are PR 8's work, not this one's").toBe("destination");
        expect(entry?.redirectsTo).toBeNull();
        expect(existsSync(path.join(routeDir(route), "page.tsx"))).toBe(true);
    });

    it("defaults every shared panel to the presentation its own route ships", () => {
        for (const panel of [
            "OrgMembersPanel.tsx",
            "OrgAllowedDomainsPanel.tsx",
            "OrgAuditPanel.tsx",
            "OrgAiProviderPanel.tsx",
            "DataRequestsPanel.tsx",
        ]) {
            expect(
                source(path.join(ORG_COMPONENTS, panel)),
                `${panel} must keep its legacy home unchanged unless a caller asks otherwise`,
            ).toContain('presentation = "page"');
        }
        expect(source(path.join(COMPONENTS, "SsoPanel.tsx"))).toContain('presentation = "page"');
    });
});
