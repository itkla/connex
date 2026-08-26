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
 *
 * It also does not assert anything about the legacy `/organization` layout. That layout carries the
 * same identity-test bug this one fixes (#1422), and pinning its current bytes here would turn a
 * gate over the new destinations into a tripwire that fails the moment the old one is repaired.
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

/**
 * Every file this suite reads, read once.
 *
 * The one-spelling scan and the id-collision scan both walk the whole `app/` tree, and re-reading it
 * per test made the pair the slowest thing in the suite — slow enough to trip the 30s timeout on a
 * loaded machine, which reads as a failure and is only contention.
 */
const sources = new Map<string, string>();

function source(file: string): string {
    const cached = sources.get(file);
    if (cached !== undefined) return cached;
    const text = readFileSync(file, "utf8");
    sources.set(file, text);
    return text;
}

/**
 * The message key the 2026-08-19 ruling retired, held as a widened `string`.
 *
 * The manifest narrows every title key to a union of what it actually names, so once the key left
 * that union a literal comparison stopped type-checking. Keeping it here as a plain string is what
 * lets the assertion below stay compilable and stay meaningful.
 */
const RETIRED_PAGE_NAME_KEY: string = "Organization.tabOverview";

/** Every `@/app/components/...` module a file imports, resolved to a path on disk. */
function componentImports(file: string): string[] {
    const matches = source(file).matchAll(/from "@\/app\/(components\/[^"]+)"/g);
    return [...matches].map((match) => path.join(APP, `${match[1]}.tsx`)).filter(existsSync);
}

/** A page's view and every component reachable from it, which is what its anchors are drawn from. */
function composedSources(view: string): string[] {
    const seen = new Set<string>();
    const queue = [view];
    while (queue.length > 0) {
        const next = queue.pop();
        if (next === undefined || seen.has(next)) continue;
        seen.add(next);
        queue.push(...componentImports(next));
    }
    return [...seen];
}

/**
 * The section slugs a page's own markup also spends on an element of its own.
 *
 * A section slug is a DOM id: {@link SettingsSectionRegion} puts it on the region wrapper so the
 * fragment resolves there. If a control inside the composed panels already carries that id, the
 * document holds it twice, the region wins on tree order, and any `htmlFor` or `aria-labelledby`
 * pointing at the control silently retargets a layout div — the label stops focusing its input and
 * the input loses its accessible name. It costs a rename, and nothing else catches it.
 */
function collidingSectionIds(
    sections: readonly string[],
    sources: readonly string[],
): readonly string[] {
    const taken = new Set<string>();
    for (const text of sources) {
        for (const match of text.matchAll(/\bid="([^"]+)"/g)) taken.add(match[1]);
    }
    return sections.filter((section) => taken.has(section));
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
        titleKey: "SettingsNav.groupDataRequests",
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

    it.each(GROUPS)("$id spends each section slug on the region and nowhere else", (group) => {
        const composed = composedSources(group.view).map(source);

        expect(
            collidingSectionIds(group.sections, composed),
            "a control already carrying this id makes the document hold it twice; the region wins on tree order, so the control's label retargets a layout div and the control loses its accessible name",
        ).toEqual([]);
    });

    it("catches the collision this rule was written for", () => {
        const panel = 'const Provider = () => <SelectTrigger id="ai-provider" className="w-full" />;';

        expect(
            collidingSectionIds(ORGANIZATION_AI_GOVERNANCE_SECTIONS, [panel]),
            "the AI provider select carried the section's own slug until this PR renamed it to ai-provider-kind",
        ).toEqual(["ai-provider"]);
        expect(
            collidingSectionIds(ORGANIZATION_AI_GOVERNANCE_SECTIONS, [
                panel.replace("ai-provider", "ai-provider-kind"),
            ]),
            "and the rename is what clears it",
        ).toEqual([]);
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

        /**
         * Two origins, not producers: the builder every consumer asks for an href, and the manifest
         * the builder reads its slugs from — which since #1340 PR 8 also records each retired
         * address's redirect target, and that target is this same deep link.
         */
        const origins = [
            path.join(APP, "lib", "organizationSettingsSections.ts"),
            path.join(APP, "lib", "settingsManifest.ts"),
        ];
        const strays = appFiles(APP)
            .filter((file) => !origins.includes(file))
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
        const grouped: readonly SettingsEntry[] = SETTINGS_ENTRIES.filter(
            (entry) =>
                entry.group?.startsWith("organization.") === true
                && entry.canonicalSection !== null
                && entry.titleKey !== null,
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
        ).toContain("activeWorkspace.orgRole == null");
        expect(
            layout.includes("activeWorkspace.orgRole === null"),
            "the payload omits the role rather than nulling it, so an identity test admits the viewer this gate exists to refuse; organizationSettingsGate.test.tsx holds the behavior",
        ).toBe(false);
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
    it("retires Organization.tabOverview from the catalog along with the strip that rendered it", () => {
        /**
         * Widened on purpose. The manifest's `as const` narrows every title key to a union the
         * retired key has already left, so comparing against the literal is a type error rather
         * than a passing assertion — and deleting the check because it "cannot match" would retire
         * the gate along with the key. Through `string` the comparison stays legal and regains its
         * meaning the moment someone puts the key back.
         */
        const named: readonly (string | null)[] = [
            ...SETTINGS_ENTRIES.map((entry) => entry.titleKey),
            ...SETTINGS_GROUPS.map((group) => group.titleKey),
        ];

        expect(
            named.filter((key) => key === RETIRED_PAGE_NAME_KEY),
            "§7 retires Overview as a page name and the 2026-08-19 ruling names this group General, so nothing the settings navigation renders may resolve to it",
        ).toEqual([]);
        expect(
            existsSync(path.join(ORG_COMPONENTS, "OrgTabs.tsx")),
            "the tab strip that was the key's last consumer is gone, and the key with it",
        ).toBe(false);
        for (const locale of ["en", "ja"]) {
            const catalog = JSON.parse(
                readFileSync(path.join(process.cwd(), "messages", locale, "organization.json"), "utf8"),
            );
            expect(
                Object.keys(catalog.Organization),
                `${locale} still carries the retired page name`,
            ).not.toContain("tabOverview");
        }
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

describe("the legacy organization routes forward to the destinations that absorbed them", () => {
    it.each([
        "/organization/overview",
        "/organization/members",
        "/organization/allowed-domains",
        "/organization/sso",
        "/organization/ai",
        "/organization/data-requests",
        "/organization/audit",
        "/organization/diagnostics",
    ])("retires %s into a permanent forward that keeps the address alive", (route) => {
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.currentRoute === route);

        expect(entry?.kind, "#1340 PR 8 is where these addresses stopped rendering").toBe("redirect");
        expect(entry?.redirectsTo).toBe(
            entry?.canonicalSection === null
                ? entry?.canonicalRoute
                : `${entry?.canonicalRoute}#${entry?.canonicalSection}`,
        );
        expect(
            existsSync(path.join(routeDir(route), "page.tsx")),
            "the page survives as a stub; deleting it would 404 an address readers have bookmarked",
        ).toBe(true);
        expect(source(path.join(routeDir(route), "page.tsx"))).toContain("permanentRedirect(");
    });

    /**
     * The `page` presentation is retained rather than exercised, and that is a deliberate hold.
     *
     * Each of these panels took its `page` default from the standalone organization route that
     * rendered it; every one of those routes now forwards, so no caller passes `page` any more and
     * the branch is unreachable in the shipped app. Collapsing six unions and the shells behind them
     * is a mechanical refactor across the panels and their pinned assertions, and doing it in the
     * same diff as the redirect matrix would mix a behavioural change with a structural one. It is
     * tracked as a residual of #1340 rather than done quietly here.
     *
     * The assertion is kept, retitled, so the branch stays intact until it is removed on purpose —
     * a half-collapsed union that still defaults to `page` would be worse than either end state.
     */
    it("retains the unexercised page presentation on every shared panel", () => {
        for (const panel of [
            "OrgMembersPanel.tsx",
            "OrgAllowedDomainsPanel.tsx",
            "OrgAuditPanel.tsx",
            "OrgAiProviderPanel.tsx",
            "DataRequestsPanel.tsx",
        ]) {
            expect(
                source(path.join(ORG_COMPONENTS, panel)),
                `${panel} keeps its default until the presentation union is collapsed deliberately`,
            ).toContain('presentation = "page"');
        }
        expect(source(path.join(COMPONENTS, "SsoPanel.tsx"))).toContain('presentation = "page"');
    });
});
