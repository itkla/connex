import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsEntry,
    type SettingsGroup,
    type SettingsScope,
} from "@/app/lib/settingsManifest";
import {
    capabilityValue,
    entryVisible,
    resolveSettingsNavigation,
    sectionVisible,
    searchSettingsNavigation,
    type SettingsNavContext,
    type SettingsNavViewer,
} from "@/app/lib/settingsNavigation";
import { settingsRouteServed } from "@/app/lib/settingsEntryPoints";
import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * Gate over the unified Settings navigation (#1340 WS4.1).
 *
 * The epic's acceptance is that the rendered navigation equals the manifest in both locales. What
 * this suite proves is the **data** the shell renders from: it builds the navigation model with the
 * real message catalogs, once per locale, and reconciles it against `SETTINGS_GROUPS` and
 * `SETTINGS_ENTRIES` directly, rather than with a mocked translator that would prove only that a
 * key was passed through. Whether every row of that model reaches the DOM is a render concern; one
 * render assertion below covers the case the data-level tests cannot distinguish — a group whose
 * single destination the directory deliberately does not list twice.
 *
 * The manifest suite cannot cover this seam: its entry-point gate scans navigation sources for
 * literal hrefs, and this navigation has none — every route it links is read from the manifest at
 * render time. A literal-href scan would score it as an empty file and pass vacuously. Equality
 * against the manifest is the honest check, and it is what these tests assert.
 */
const LOCALES = ["en", "ja"] as const;

const groups: readonly SettingsGroup[] = SETTINGS_GROUPS;
const entries: readonly SettingsEntry[] = SETTINGS_ENTRIES;

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

/** Every message file of a locale, shallow-merged the way the request-time loader merges them. */
function messageCatalog(locale: string): Record<string, unknown> {
    const directory = path.join(process.cwd(), "messages", locale);
    const merged: Record<string, unknown> = {};
    for (const file of readdirSync(directory).sort()) {
        if (!file.endsWith(".json")) continue;
        const parsed: unknown = JSON.parse(readFileSync(path.join(directory, file), "utf8"));
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
            throw new Error(`messages/${locale}/${file} is not an object`);
        }
        Object.assign(merged, parsed);
    }
    return merged;
}

function resolveMessage(catalog: Record<string, unknown>, key: string): string {
    let current: unknown = catalog;
    for (const segment of key.split(".")) {
        if (typeof current !== "object" || current === null || Array.isArray(current)) {
            throw new Error(`unresolved message key ${key}`);
        }
        current = (current as Record<string, unknown>)[segment];
    }
    if (typeof current !== "string") throw new Error(`unresolved message key ${key}`);
    return current;
}

const catalogs = new Map(LOCALES.map((locale) => [locale, messageCatalog(locale)]));

/** Every permission any manifest entry gates its content on. */
const EVERY_VISIBILITY_PERMISSION = new Set(
    entries.flatMap((entry) => [...entry.access.permissions]),
);

function viewer(overrides: Partial<SettingsNavViewer> = {}): SettingsNavViewer {
    return {
        capabilities: ALL_CAPABILITIES,
        permissions: EVERY_VISIBILITY_PERMISSION,
        isOrgAdmin: true,
        ...overrides,
    };
}

function context(locale: (typeof LOCALES)[number], overrides: Partial<SettingsNavViewer> = {}): SettingsNavContext {
    const catalog = catalogs.get(locale);
    if (!catalog) throw new Error(`no catalog for ${locale}`);
    return {
        viewer: viewer(overrides),
        translate: (key) => resolveMessage(catalog, key),
        scopeNames: {
            personal: resolveMessage(catalog, "SettingsNav.scopePersonal"),
            workspace: resolveMessage(catalog, "SettingsNav.scopeWorkspace"),
            organization: resolveMessage(catalog, "SettingsNav.scopeOrganization"),
        },
        workspaceName: "Northstar",
        organizationName: "Klae",
    };
}

/** The manifest's own answer to what the navigation should show a fully-authorized viewer. */
function expectedGroups(scope: SettingsScope): string[] {
    return groups.filter((group) => group.scope === scope)
        .slice()
        .sort((left, right) => left.order - right.order)
        .map((group) => group.id);
}

/** Whether the navigation could offer this entry as a row inside a scope group. */
function offerable(entry: SettingsEntry): boolean {
    return (
        entry.group !== null
        && entry.kind === "destination"
        && entry.titleKey !== null
        && !entry.currentRoute.includes("[")
    );
}

/** The manifest's canonical route for a group. */
function groupRoute(groupId: string): string {
    const group = groups.find((candidate) => candidate.id === groupId);
    if (!group) throw new Error(`no manifest group ${groupId}`);
    return group.route;
}

/**
 * What a group offers, as the manifest says it: the destination serving its canonical route once
 * that route exists, and otherwise every offerable entry filed under it. Derived rather than
 * restated, so the suite follows each group across its migration instead of pinning today's shape.
 */
function offeredEntries(groupId: string): SettingsEntry[] {
    const offerable_ = entries.filter((entry) => entry.group === groupId && offerable(entry));
    const canonical = offerable_.find((entry) => entry.currentRoute === groupRoute(groupId));
    return canonical ? [canonical] : offerable_;
}

/** Whether every capability an entry declares resolves the way it needs for this viewer. */
function capabilitiesHold(entry: SettingsEntry, who: SettingsNavViewer): boolean {
    const { capabilities, capabilityMatch } = entry.access;
    if (capabilities.length === 0) return true;
    const resolved = who.capabilities;
    if (resolved === null) return true;
    const holds = (requirement: (typeof capabilities)[number]) =>
        capabilityValue(resolved, requirement.key) === requirement.expected;
    return capabilityMatch === "all" ? capabilities.every(holds) : capabilities.some(holds);
}

/**
 * The section ids a served canonical destination offers: the jobs it absorbed, then the route gaps
 * its group declares. Derived from the manifest so the suite follows a group across its migration.
 *
 * An absorbed job counts whatever its old address does now. Before #1340 PR 8 those addresses still
 * rendered; after it, most of them forward here — and a job does not stop being a section of this
 * page because the route it arrived from became a redirect. Filtering on kind here would have made
 * this expectation agree with a `groupSections` that had quietly emptied itself, which is the
 * failure both were written to catch.
 */
function expectedSectionIds(groupId: string, who: SettingsNavViewer = viewer()): string[] {
    const route = groupRoute(groupId);
    if (!entries.some((entry) => entry.group === groupId && offerable(entry) && entry.currentRoute === route)) {
        return [];
    }
    const group = groups.find((candidate) => candidate.id === groupId);
    const absorbed = entries
        .filter(
            (entry) =>
                entry.group === groupId
                && entry.canonicalSection !== null
                && entry.canonicalRoute === route
                && entry.titleKey !== null
                && !(entry.access.orgAdmin && !who.isOrgAdmin)
                && capabilitiesHold(entry, who),
        )
        .map((entry) => entry.id);
    const gaps = (group?.gapSections ?? []).map((section) => `${groupId}#${section.slug}`);
    return [...absorbed, ...gaps];
}

describe("settings navigation equals the manifest", () => {
    it.each(LOCALES)("renders every scope, group, and destination the manifest declares in %s", (locale) => {
        const model = resolveSettingsNavigation(context(locale));

        expect(model.map((scope) => scope.scope)).toEqual(["personal", "workspace", "organization"]);
        for (const scope of model) {
            expect(
                scope.groups.map((group) => group.id),
                `${scope.scope} groups must equal the manifest's, in its order`,
            ).toEqual(expectedGroups(scope.scope));
            for (const group of scope.groups) {
                const manifestEntries = offeredEntries(group.id);
                expect(group.destinations.map((destination) => destination.id)).toEqual(
                    manifestEntries.map((entry) => entry.id),
                );
                expect(group.destinations.map((destination) => destination.href)).toEqual(
                    manifestEntries.map((entry) => entry.currentRoute),
                );
                expect(group.sections.map((section) => section.id)).toEqual(expectedSectionIds(group.id));
            }
        }
    });

    it.each(LOCALES)("labels every group and destination from the shipped catalog in %s", (locale) => {
        const catalog = catalogs.get(locale);
        if (!catalog) throw new Error(`no catalog for ${locale}`);
        const model = resolveSettingsNavigation(context(locale));
        const groupsById = new Map(groups.map((group) => [group.id, group]));
        const entriesById = new Map(entries.map((entry) => [entry.id, entry]));

        for (const scope of model) {
            for (const group of scope.groups) {
                const titleKey = groupsById.get(group.id)?.titleKey;
                expect(titleKey, `${group.id} must carry a label key once the shell renders it`).not.toBeNull();
                expect(group.title).toBe(resolveMessage(catalog, titleKey ?? ""));
                expect(group.title.trim()).not.toBe("");
                for (const destination of group.destinations) {
                    const key = entriesById.get(destination.id)?.titleKey;
                    expect(destination.title).toBe(resolveMessage(catalog, key ?? ""));
                }
            }
        }
    });

    it("carries no group whose label the shell never authored", () => {
        const unauthored = groups.filter((group) => group.titleKey === null).map((group) => group.id);

        expect(
            unauthored,
            "every scope group the navigation renders needs a label key; the shell PR authors the ones the manifest left null",
        ).toEqual([]);
    });

    it.each(LOCALES)("qualifies the workspace and organization headings by name in %s", (locale) => {
        const model = resolveSettingsNavigation(context(locale));
        const byScope = new Map(model.map((scope) => [scope.scope, scope]));

        expect(byScope.get("personal")?.qualifier).toBeNull();
        expect(byScope.get("personal")?.label).toBe(byScope.get("personal")?.name);
        expect(byScope.get("workspace")?.label).toBe(`${byScope.get("workspace")?.name} · Northstar`);
        expect(byScope.get("organization")?.label).toBe(`${byScope.get("organization")?.name} · Klae`);
    });

    it("differs between the two locales, so a locale-blind pass cannot satisfy this suite", () => {
        const english = resolveSettingsNavigation(context("en"));
        const japanese = resolveSettingsNavigation(context("ja"));
        const titles = (model: ReturnType<typeof resolveSettingsNavigation>) =>
            model.flatMap((scope) => scope.groups.map((group) => group.title));

        expect(titles(english)).not.toEqual(titles(japanese));
        expect(titles(japanese).every((title) => /[^\x00-\x7F]/.test(title))).toBe(true);
    });
});

describe("settings navigation gates on the manifest's visibility bucket", () => {
    it("hides a destination whose visibility permission the viewer lacks", () => {
        const model = resolveSettingsNavigation(context("en", { permissions: new Set() }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids).not.toContain("workspace.roles");
        expect(ids).not.toContain("workspace.general");
        expect(ids).not.toContain("workspace.audit-log");
    });

    it("offers a consolidated destination to a reader who can read either of the jobs it merged", () => {
        const auditor = resolveSettingsNavigation(
            context("en", { permissions: new Set(["AUDIT_READ"]) }),
        );
        const administrator = resolveSettingsNavigation(
            context("en", { permissions: new Set(["WORKSPACE_SETTINGS"]) }),
        );
        const offered = (model: ReturnType<typeof resolveSettingsNavigation>) =>
            model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(
            offered(auditor),
            "a role granted exactly the audit permission must not lose the audit log to the page named for it",
        ).toContain("workspace.audit-diagnostics");
        expect(
            offered(administrator),
            "and a workspace administrator without it keeps the diagnostics half",
        ).toContain("workspace.audit-diagnostics");
    });

    it("still hides a consolidated destination from a reader who can read neither job", () => {
        const model = resolveSettingsNavigation(
            context("en", { permissions: new Set(["DEAL_CREATE"]) }),
        );
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(
            ids,
            "an any-of gate widens who reaches the page; it does not open it to everyone",
        ).not.toContain("workspace.audit-diagnostics");
    });

    it("would hide the auditor if the gate were strict, which is why the manifest loosens it", () => {
        const entry = entries.find((candidate) => candidate.id === "workspace.audit-diagnostics");
        if (!entry) throw new Error("the consolidated audit destination is the subject of this probe");
        const strict: SettingsEntry = {
            ...entry,
            access: { ...entry.access, permissionMatch: "all" },
        };
        const auditor: SettingsNavViewer = {
            capabilities: ALL_CAPABILITIES,
            permissions: new Set(["AUDIT_READ"]),
            isOrgAdmin: false,
        };

        expect(entryVisible(entry, auditor)).toBe(true);
        expect(
            entryVisible(strict, auditor),
            "the probe proves the loosening is load-bearing rather than decorative",
        ).toBe(false);
    });

    it("keeps a destination whose manage permission the viewer lacks", () => {
        const model = resolveSettingsNavigation(context("en", { permissions: new Set() }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));
        const sectionIds = model.flatMap((scope) => scope.groups.flatMap((group) => group.sections.map((s) => s.id)));

        expect(ids, "manage-only gates stop writes, not reading; hiding them would hide a working page").toContain(
            "workspace.people",
        );
        expect(
            sectionIds,
            "the roster's own job survives its consolidation; a viewer who cannot invite still reads the members",
        ).toContain("workspace.members");
        expect(ids).toContain("workspace.data-privacy");
        expect(
            ids,
            "CRM configuration absorbed the approval-policy browser, which is manage-gated the same way",
        ).toContain("workspace.crm");
        expect(
            sectionIds,
            "and the browser's own job survives that consolidation as a named section of it",
        ).toContain("workspace.approval-policies");
    });

    it("drops the organization scope entirely for a viewer holding no organization role", () => {
        const model = resolveSettingsNavigation(context("en", { isOrgAdmin: false }));

        expect(model.map((scope) => scope.scope)).toEqual(["personal", "workspace"]);
    });

    it("keeps a capability-gated destination that can explain the capability resolving against it", () => {
        const managedMail: InstanceCapabilities = { ...ALL_CAPABILITIES, mailManaged: true };
        const model = resolveSettingsNavigation(context("en", { capabilities: managedMail }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));
        const email = entries.find((entry) => entry.id === "workspace.email");

        expect(
            email?.access.states,
            "Email keeps its place because it can say it is instance-managed; that claim is what makes it visible",
        ).toContain("managed");
        const sectionIds = model.flatMap((scope) =>
            scope.groups.flatMap((group) => group.sections.map((section) => section.id)),
        );

        expect(
            sectionIds,
            "#1340: a capability-managed destination never silently vanishes — it explains itself where its name is, which is now a section of Communications",
        ).toContain("workspace.email");
        expect(sectionIds).toContain("workspace.delivery");
        expect(
            ids,
            "and the destination holding both stays offered, so that explanation is reachable",
        ).toContain("workspace.communications");
    });

    it("keeps a destination whose any-of capabilities all resolved against it", () => {
        const noProviders: InstanceCapabilities = {
            ...ALL_CAPABILITIES,
            connectedAccounts: { google: false, microsoft: false },
            connectedCapture: { google: false, microsoft: false },
        };
        const model = resolveSettingsNavigation(context("en", { capabilities: noProviders }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids).toContain("personal.connected-accounts");
        expect(ids).toContain("personal.profile");
    });

    it("keeps every destination the two retired teleports used to hide", () => {
        const noCapabilities: InstanceCapabilities = {
            sso: false,
            socialLogin: { google: false, microsoft: false },
            connectedAccounts: { google: false, microsoft: false },
            connectedCapture: { google: false, microsoft: false },
            mailManaged: true,
            businessCardScanning: false,
            businessCardImport: false,
            campaignDelivery: false,
        };
        const model = resolveSettingsNavigation(context("en", { capabilities: noCapabilities }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        const sectionIds = model.flatMap((scope) =>
            scope.groups.flatMap((group) => group.sections.map((section) => section.id)),
        );

        expect(sectionIds, "email is a section of Communications now, and still named").toContain(
            "workspace.email",
        );
        expect(ids).toContain("workspace.communications");
        expect(
            sectionIds,
            "single sign-on is a section of Identity & administrators now, and an instance without it still names the section rather than dropping it",
        ).toContain("organization.sso");
        expect(ids).toContain("organization.identity");
        expect(
            entries.filter((entry) => entry.conditionalForward !== null).map((entry) => entry.id),
            "these two are visible because neither page forwards away from its own state any more",
        ).toEqual([]);
    });

    it("still hides a capability-gated destination that declares no state to explain", () => {
        const silent: SettingsEntry = {
            ...(entries.find((entry) => entry.id === "workspace.email") ?? entries[0]),
            id: "test.silent",
            access: {
                permissions: [],
                permissionMatch: "all",
                capabilities: [{ key: "sso", expected: true }],
                capabilityMatch: "all",
                orgAdmin: false,
                manage: [],
                orgWrite: null,
                states: [],
            },
        };
        const noSso: InstanceCapabilities = { ...ALL_CAPABILITIES, sso: false };

        expect(
            entryVisible(silent, viewer({ capabilities: noSso })),
            "a destination with nothing to say about being off is not worth advertising",
        ).toBe(false);
        expect(entryVisible(silent, viewer({ capabilities: ALL_CAPABILITIES }))).toBe(true);
        expect(
            entries.filter(
                (entry) => entry.access.capabilities.length > 0 && entry.access.states.length === 0,
            ),
            "no shipped entry is in this shape — the manifest gate requires a capability-gated entry to explain itself — so the rule is proven on a constructed one",
        ).toEqual([]);
    });

    it("keeps every capability-gated destination when the capability lookup failed", () => {
        const resolved = resolveSettingsNavigation(context("en"));
        const unresolved = resolveSettingsNavigation(context("en", { capabilities: null }));
        const ids = (model: ReturnType<typeof resolveSettingsNavigation>) =>
            model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));
        const sectionIds = (model: ReturnType<typeof resolveSettingsNavigation>) =>
            model.flatMap((scope) => scope.groups.flatMap((group) => group.sections.map((s) => s.id)));

        expect(ids(unresolved)).toEqual(ids(resolved));
        expect(sectionIds(unresolved)).toEqual(sectionIds(resolved));
        expect(sectionIds(unresolved)).toContain("organization.sso");
    });

    it("reads every capability key the manifest can name", () => {
        const keys = entries.flatMap((entry) =>
            entry.access.capabilities.map((requirement) => requirement.key),
        );

        expect(keys.length).toBeGreaterThan(0);
        for (const key of keys) {
            expect(typeof capabilityValue(ALL_CAPABILITIES, key)).toBe("boolean");
        }
    });

    it("never offers a destination the manifest cannot address", () => {
        const detail = entries.find((entry) => entry.id === "workspace.people-detail");
        const model = resolveSettingsNavigation(context("en"));
        const hrefs = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.href)));

        expect(detail?.kind).toBe("destination");
        expect(hrefs.some((href) => href.includes("["))).toBe(false);
        expect(hrefs).not.toContain(detail?.currentRoute);
    });

    it("gates a section on the scope gates its entry declares, so a name is no more disclosed than a row", () => {
        const section = (access: Partial<SettingsEntry["access"]>): SettingsEntry => ({
            id: "probe",
            currentRoute: "/settings/probe",
            kind: "destination",
            group: "workspace.people",
            canonicalRoute: "/settings/workspace/people",
            canonicalSection: "probe",
            redirectsTo: null,
            redirectQuery: [],
            conditionalForward: null,
            titleKey: "WorkspaceSettings.tabRoles",
            access: {
                permissions: [],
                permissionMatch: "all",
                capabilities: [],
                capabilityMatch: "all",
                orgAdmin: false,
                manage: [],
                orgWrite: null,
                states: [],
                ...access,
            },
            entryPoints: [],
            aliasKey: null,
        });

        expect(
            sectionVisible(section({ orgAdmin: true }), viewer({ isOrgAdmin: false })),
            "an organization-gated job's name must not reach a viewer who holds no organization role",
        ).toBe(false);
        expect(sectionVisible(section({ orgAdmin: true }), viewer({ isOrgAdmin: true }))).toBe(true);
        expect(
            sectionVisible(
                section({ capabilities: [{ key: "sso", expected: true }] }),
                viewer({ capabilities: { ...ALL_CAPABILITIES, sso: false } }),
            ),
            "a section of a capability the deployment does not have is not a section",
        ).toBe(false);
        expect(
            sectionVisible(section({ permissions: ["ROLE_MANAGE"] }), viewer({ permissions: new Set() })),
            "permissions are the deliberate carve-out: the page explains that refusal in place",
        ).toBe(true);
    });

    it("actually applies that predicate where sections are built", () => {
        const source = readFileSync(path.join(process.cwd(), "app", "lib", "settingsNavigation.ts"), "utf8");
        const builder = source.slice(source.indexOf("function groupSections("));

        expect(
            builder.slice(0, builder.indexOf("\n}")),
            "groupSections must filter on sectionVisible; today no served group has a scope-gated section, so the model-level invariant above cannot yet see this wiring and would pass without it",
        ).toContain("sectionVisible(entry, context.viewer)");
    });

    it("still names a permission-refused section, because the reader must find it to be told to ask", () => {
        const model = resolveSettingsNavigation(context("en", { permissions: new Set() }));
        const sections = model.flatMap((scope) => scope.groups.flatMap((group) => group.sections));

        expect(
            sections.map((section) => section.id),
            "roles is permission-gated and explains itself in place; hiding its name would strand the reader",
        ).toContain("workspace.roles");
    });

    it("matches groupSections against the same gates for every group, not just the migrated one", () => {
        for (const who of [viewer(), viewer({ isOrgAdmin: false }), viewer({ permissions: new Set() })]) {
            const model = resolveSettingsNavigation({ ...context("en"), viewer: who });
            const byGroup = new Map(
                model.flatMap((scope) => scope.groups).map((group) => [group.id, group.sections.map((s) => s.id)]),
            );
            for (const group of groups) {
                expect(byGroup.get(group.id) ?? [], `${group.id} sections`).toEqual(
                    byGroup.has(group.id) ? expectedSectionIds(group.id, who) : [],
                );
            }
        }
    });

    it("agrees with entryVisible on every entry it renders", () => {
        const restricted = viewer({ permissions: new Set(["WORKSPACE_SETTINGS"]), isOrgAdmin: false });
        const model = resolveSettingsNavigation({ ...context("en"), viewer: restricted });
        const rendered = new Set(
            model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id))),
        );
        const expected = new Set(
            groups
                .flatMap((group) => offeredEntries(group.id))
                .filter((entry) => entryVisible(entry, restricted))
                .map((entry) => entry.id),
        );

        expect([...rendered].sort()).toEqual([...expected].sort());
    });
});

describe("settings navigation resolves a landing for every group", () => {
    it("links each group to the first destination it offers", () => {
        const model = resolveSettingsNavigation(context("en"));

        for (const scope of model) {
            for (const group of scope.groups) {
                expect(group.href).toBe(group.destinations[0]?.href);
                expect(group.destinations.length).toBeGreaterThan(0);
            }
        }
    });

    it("lands a migrated group on the canonical destination it now owns, not on the first route it used to hold", () => {
        const model = resolveSettingsNavigation(context("en"));
        const people = model
            .flatMap((scope) => scope.groups)
            .find((group) => group.id === "workspace.people");

        expect(people?.href).toBe("/settings/workspace/people");
        expect(people?.href).not.toBe("/settings/members");
        expect(
            people?.destinations.map((destination) => destination.id),
            "a group that owns its canonical destination offers that one, not it and the addresses it absorbed",
        ).toEqual(["workspace.people"]);
    });

    it("keeps every absorbed job addressable at its section of the destination that took it", () => {
        const model = resolveSettingsNavigation(context("en"));
        const people = model
            .flatMap((scope) => scope.groups)
            .find((group) => group.id === "workspace.people");

        expect(people?.sections.map((section) => section.id)).toEqual([
            "workspace.members",
            "workspace.roles",
            "workspace.people-directory",
            "workspace.people#allowed-domains",
        ]);
        expect(
            people?.sections.map((section) => section.href),
            "the route gap the group declares is addressable on the same terms as the jobs it absorbed",
        ).toEqual([
            "/settings/workspace/people#members",
            "/settings/workspace/people#roles",
            "/settings/workspace/people#directory",
            "/settings/workspace/people#allowed-domains",
        ]);
    });

    /**
     * The rule this guards is that `groupSections` yields nothing until a group's canonical route is
     * served — otherwise settings search would advertise deep links into a page that does not exist.
     * It used to be shown against the manifest's own unmigrated groups; there are none left, so an
     * assertion over that filter would now pass by iterating an empty list.
     *
     * What replaces it is the pair of facts the rule rests on, neither of which is vacuous: that
     * `settingsRouteServed` still answers no for an address nothing serves, which is the test the
     * rule keys off; and that every section a group does offer is a fragment of that group's own
     * canonical route, so no section can escape into an address its destination does not serve.
     */
    it("gives an unmigrated group no sections, so the rule cannot pass by applying to everything", () => {
        const model = resolveSettingsNavigation(context("en"));
        const unmigrated = model
            .flatMap((scope) => scope.groups)
            .filter((group) => group.destinations[0]?.href !== groupRoute(group.id));

        expect(
            unmigrated.map((group) => group.id),
            "every group #1340 names is served by its canonical route now",
        ).toEqual([]);
        expect(
            settingsRouteServed("/settings/personal/unbuilt"),
            "the served-or-not test the rule keys off still answers, and answers no for an address nothing serves",
        ).toBe(false);
        expect(
            model
                .flatMap((scope) => scope.groups)
                .flatMap((group) =>
                    group.sections
                        .filter((section) => !section.href.startsWith(`${groupRoute(group.id)}#`))
                        .map((section) => `${group.id} -> ${section.href}`),
                ),
            "a section is a deep link into the destination that absorbed it, never into another",
        ).toEqual([]);
    });

    it("puts a real destination first, so the home never lands the reader on Members", () => {
        const model = resolveSettingsNavigation(context("en"));
        const first = model[0]?.groups[0]?.destinations[0];

        expect(first?.href).toBe("/settings/personal/profile");
        expect(first?.href).not.toBe("/settings/members");
    });

    it("still offers the personal scope to a viewer holding nothing", () => {
        const model = resolveSettingsNavigation({
            ...context("en"),
            viewer: { capabilities: ALL_CAPABILITIES, permissions: new Set(), isOrgAdmin: false },
        });

        expect(model[0]?.scope).toBe("personal");
        expect(model[0]?.groups.length).toBeGreaterThan(0);
    });
});

describe("settings search finds destinations by every name they carry", () => {
    it.each(LOCALES)("finds a destination by its own label in %s", (locale) => {
        const catalog = catalogs.get(locale);
        if (!catalog) throw new Error(`no catalog for ${locale}`);
        const model = resolveSettingsNavigation(context(locale));
        const roles = resolveMessage(catalog, "WorkspaceSettings.tabRoles");
        const results = searchSettingsNavigation(model, roles);

        expect(
            results.map((result) => result.id),
            "consolidation moves where a job lives; the word the reader knows it by must still find it",
        ).toContain("workspace.roles");
        expect(results.find((result) => result.id === "workspace.roles")?.href).toBe(
            "/settings/workspace/people#roles",
        );
    });

    it.each(LOCALES)("finds a destination by the group that now owns it in %s", (locale) => {
        const catalog = catalogs.get(locale);
        if (!catalog) throw new Error(`no catalog for ${locale}`);
        const model = resolveSettingsNavigation(context(locale));
        const results = searchSettingsNavigation(model, resolveMessage(catalog, "SettingsNav.groupPeopleAccess"));

        expect(results.map((result) => result.id).sort()).toEqual(
            [
                "workspace.members",
                "workspace.people",
                "workspace.people#allowed-domains",
                "workspace.people-directory",
                "workspace.roles",
            ].sort(),
        );
    });

    it("finds a destination by a command-palette alias the reader would type", () => {
        const model = resolveSettingsNavigation(context("en"));

        expect(
            searchSettingsNavigation(model, "queue").map((result) => result.id),
            "the review queue's palette aliases follow it into the section that now holds it",
        ).toEqual(["account.capture-reviews"]);
        expect(searchSettingsNavigation(model, "diagnostics").length).toBeGreaterThan(0);
    });

    it.each(LOCALES)("keeps the retired directory label as a hidden migration alias in %s", (locale) => {
        const catalog = catalogs.get(locale);
        if (!catalog) throw new Error(`no catalog for ${locale}`);
        const model = resolveSettingsNavigation(context(locale));
        const retiredLabel = resolveMessage(catalog, "Actions.keywords.navigate.users").split(",")[0] ?? "";
        const result = searchSettingsNavigation(model, retiredLabel)
            .find((candidate) => candidate.id === "workspace.people-directory");

        expect(result?.href).toBe("/settings/workspace/people#directory");
        expect(result?.title).toBe(resolveMessage(catalog, "SettingsPeople.directoryTitle"));
    });

    it("matches without regard to case and reports nothing for a blank query", () => {
        const model = resolveSettingsNavigation(context("en"));

        expect(searchSettingsNavigation(model, "ROLES").map((result) => result.id)).toContain("workspace.roles");
        expect(searchSettingsNavigation(model, "   ")).toEqual([]);
    });

    it("places every result under the scope and group that own it", () => {
        const model = resolveSettingsNavigation(context("en"));
        const [result] = searchSettingsNavigation(model, "Single sign-on");

        expect(result?.scopeLabel).toBe("Organization · Klae");
        expect(result?.groupTitle).toBe("Identity & administrators");
    });

    it("finds nothing a fully-gated viewer may not reach", () => {
        const model = resolveSettingsNavigation(context("en", { isOrgAdmin: false }));

        expect(searchSettingsNavigation(model, "Single sign-on")).toEqual([]);
    });
});
