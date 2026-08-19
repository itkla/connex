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
    searchSettingsNavigation,
    type SettingsNavContext,
    type SettingsNavViewer,
} from "@/app/lib/settingsNavigation";
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
                const manifestEntries = entries.filter(
                    (entry) => entry.group === group.id && offerable(entry),
                );
                expect(group.destinations.map((destination) => destination.id)).toEqual(
                    manifestEntries.map((entry) => entry.id),
                );
                expect(group.destinations.map((destination) => destination.href)).toEqual(
                    manifestEntries.map((entry) => entry.currentRoute),
                );
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

    it("keeps a destination whose manage permission the viewer lacks", () => {
        const model = resolveSettingsNavigation(context("en", { permissions: new Set() }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids, "manage-only gates stop writes, not reading; hiding them would hide a working page").toContain(
            "workspace.members",
        );
        expect(ids).toContain("workspace.data");
        expect(ids).toContain("workspace.approval-policies");
    });

    it("drops the organization scope entirely for a viewer holding no organization role", () => {
        const model = resolveSettingsNavigation(context("en", { isOrgAdmin: false }));

        expect(model.map((scope) => scope.scope)).toEqual(["personal", "workspace"]);
    });

    it("hides a capability-gated destination whose capability resolved against it", () => {
        const managedMail: InstanceCapabilities = { ...ALL_CAPABILITIES, mailManaged: true };
        const model = resolveSettingsNavigation(context("en", { capabilities: managedMail }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids, "the shipped tab strip hides Email on a managed-mail instance").not.toContain("workspace.email");
        expect(ids).toContain("workspace.delivery");
    });

    it("hides a destination whose any-of capabilities all resolved against it", () => {
        const noProviders: InstanceCapabilities = {
            ...ALL_CAPABILITIES,
            connectedAccounts: { google: false, microsoft: false },
            connectedCapture: { google: false, microsoft: false },
        };
        const model = resolveSettingsNavigation(context("en", { capabilities: noProviders }));
        const ids = model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids).not.toContain("account.connections");
        expect(ids).toContain("account.profile");
    });

    it("keeps every capability-gated destination when the capability lookup failed", () => {
        const resolved = resolveSettingsNavigation(context("en"));
        const unresolved = resolveSettingsNavigation(context("en", { capabilities: null }));
        const ids = (model: ReturnType<typeof resolveSettingsNavigation>) =>
            model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id)));

        expect(ids(unresolved)).toEqual(ids(resolved));
        expect(ids(unresolved)).toContain("organization.sso");
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

    it("agrees with entryVisible on every entry it renders", () => {
        const restricted = viewer({ permissions: new Set(["WORKSPACE_SETTINGS"]), isOrgAdmin: false });
        const model = resolveSettingsNavigation({ ...context("en"), viewer: restricted });
        const rendered = new Set(
            model.flatMap((scope) => scope.groups.flatMap((group) => group.destinations.map((d) => d.id))),
        );
        const expected = new Set(
            entries.filter((entry) => offerable(entry) && entryVisible(entry, restricted)).map(
                (entry) => entry.id,
            ),
        );

        expect([...rendered].sort()).toEqual([...expected].sort());
    });
});

describe("settings navigation resolves a landing for every group", () => {
    it("links each group to the first destination the manifest files under it", () => {
        const model = resolveSettingsNavigation(context("en"));

        for (const scope of model) {
            for (const group of scope.groups) {
                expect(group.href).toBe(group.destinations[0]?.href);
                expect(group.destinations.length).toBeGreaterThan(0);
            }
        }
    });

    it("puts a real destination first, so the home never lands the reader on Members", () => {
        const model = resolveSettingsNavigation(context("en"));
        const first = model[0]?.groups[0]?.destinations[0];

        expect(first?.href).toBe("/account/profile");
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

        expect(results.map((result) => result.id)).toContain("workspace.roles");
        expect(results.find((result) => result.id === "workspace.roles")?.href).toBe("/settings/roles");
    });

    it.each(LOCALES)("finds a destination by the group that now owns it in %s", (locale) => {
        const catalog = catalogs.get(locale);
        if (!catalog) throw new Error(`no catalog for ${locale}`);
        const model = resolveSettingsNavigation(context(locale));
        const results = searchSettingsNavigation(model, resolveMessage(catalog, "SettingsNav.groupPeopleAccess"));

        expect(results.map((result) => result.id).sort()).toEqual(
            ["workspace.members", "workspace.people-directory", "workspace.roles"].sort(),
        );
    });

    it("finds a destination by a command-palette alias the reader would type", () => {
        const model = resolveSettingsNavigation(context("en"));

        expect(searchSettingsNavigation(model, "queue").map((result) => result.id)).toEqual([]);
        expect(searchSettingsNavigation(model, "diagnostics").length).toBeGreaterThan(0);
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
