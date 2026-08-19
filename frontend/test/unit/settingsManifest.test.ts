import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    SETTINGS_HOME_ROUTE,
    SETTINGS_ROUTE_ROOTS,
    type SettingsEntry,
    type SettingsEntryPoint,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";
import { SHIPPED_APP_ROUTES } from "@/app/lib/routeManifest";

/**
 * Gate over the committed settings/navigation manifest (#1340). The manifest is the single source of
 * navigation truth for every settings, account, and administration destination, so this suite proves
 * it still describes the app: every routed page under the settings roots is registered, every
 * registered route still exists, every label key resolves in both locales, every permission it names
 * is a real backend constant, no two destinations claim the same canonical owner, and the navigation
 * surfaces that link into settings agree with the entry points it declares.
 *
 * Its one blind spot is deliberate: `contextual` entry points are not verified against source, because
 * a contextual shortcut may be a computed href anywhere in the app. The registry surfaces — the three
 * tab strips, the sidebar and user menu, and the command palette — are verified in both directions.
 */
const APP_DIRECTORY = path.join(process.cwd(), "app", "(app)");

const PERMISSION_ENUM_PATH = path.join(
    process.cwd(),
    "..",
    "backend",
    "src",
    "main",
    "java",
    "ooo",
    "klae",
    "connex",
    "backend",
    "tenant",
    "Permission.java",
);

const REGISTER_ENTRY =
    "register it in SETTINGS_ENTRIES in app/lib/settingsManifest.ts, with its scope group, its gates, and its entry points";

const ENTRY_POINT_SOURCES: Record<Exclude<SettingsEntryPoint, "contextual">, readonly string[]> = {
    "account-tabs": ["app/components/account/AccountTabs.tsx"],
    "settings-tabs": ["app/components/settings/SettingsTabs.tsx"],
    "organization-tabs": ["app/components/organization/OrgTabs.tsx"],
    sidebar: ["app/components/Sidebar.tsx"],
    "avatar-menu": ["app/components/Sidebar.tsx"],
    "command-palette": [
        "app/lib/actions/seedActions.ts",
        "app/components/actions/NavActionsBridge.tsx",
    ],
};

const HREF_PATTERN = /href[=:]\s*\{?["'](\/[^"'\s]*)["']/g;
const PALETTE_PATTERN =
    /(?:navigateAction\(\s*"[^"]+",\s*"[^"]+",\s*"(\/[^"]*)"|router\.(?:push|replace)\(\s*"(\/[^"]*)")/g;

/** Every App Router page under a directory, as `/segment/[param]` patterns. */
function appRouterRoutes(directory: string, segments: string[]): string[] {
    const routes: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isFile() && entry.name === "page.tsx") {
            routes.push(`/${segments.join("/")}`);
            continue;
        }
        if (!entry.isDirectory()) continue;
        routes.push(...appRouterRoutes(path.join(directory, entry.name), [...segments, entry.name]));
    }
    return routes;
}

/** The routed pages on disk under the roots the manifest claims to cover. */
function routedSettingsPages(): string[] {
    return SETTINGS_ROUTE_ROOTS.flatMap((root) =>
        appRouterRoutes(path.join(APP_DIRECTORY, root), [root]),
    ).sort();
}

function readSource(file: string): string {
    return readFileSync(path.join(process.cwd(), file), "utf8");
}

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

/** Resolves a dotted message key to its string, or null when the catalog does not carry one. */
function resolveMessage(catalog: Record<string, unknown>, key: string): string | null {
    let current: unknown = catalog;
    for (const segment of key.split(".")) {
        if (typeof current !== "object" || current === null || Array.isArray(current)) return null;
        current = (current as Record<string, unknown>)[segment];
    }
    return typeof current === "string" ? current : null;
}

/** The constants declared by the backend `Permission` enum. */
function backendPermissions(): Set<string> {
    const source = readFileSync(PERMISSION_ENUM_PATH, "utf8");
    return new Set([...source.matchAll(/^ {4}([A-Z][A-Z0-9_]+)\s*[,;]\s*$/gm)].map((match) => match[1]));
}

/** Every literal route linked from a navigation source file. */
function linkedRoutes(file: string): string[] {
    const source = readSource(file);
    const hrefs = [...source.matchAll(HREF_PATTERN)].map((match) => match[1]);
    const pushes = [...source.matchAll(PALETTE_PATTERN)].map((match) => match[1] ?? match[2]);
    return [...hrefs, ...pushes];
}

/** Whether a route's first segment is one of the roots the manifest owns. */
function underSettingsRoot(route: string): boolean {
    const [first] = route.split("?")[0].split("#")[0].split("/").filter(Boolean);
    return first !== undefined && (SETTINGS_ROUTE_ROOTS as readonly string[]).includes(first);
}

const entries: readonly SettingsEntry[] = SETTINGS_ENTRIES;
const groups: readonly SettingsGroup[] = SETTINGS_GROUPS;
const diskRoutes = routedSettingsPages();
const registeredRoutes = new Set(entries.map((entry) => entry.currentRoute));
const groupsById = new Map(groups.map((group) => [group.id, group]));
const english = messageCatalog("en");
const japanese = messageCatalog("ja");
const permissions = backendPermissions();

describe("settings manifest structure", () => {
    it("declares unique ids and one entry per current route, in route order", () => {
        const ids = entries.map((entry) => entry.id);
        const routes = entries.map((entry) => entry.currentRoute);

        expect(ids).toEqual([...new Set(ids)]);
        expect(routes).toEqual([...new Set(routes)].sort());
    });

    it("declares unique group ids and unique canonical group routes", () => {
        const ids = groups.map((group) => group.id);
        const routes = groups.map((group) => group.route);

        expect(ids).toEqual([...new Set(ids)]);
        expect(routes).toEqual([...new Set(routes)]);
    });

    it("resolves every entry's group and points it at that group's canonical route", () => {
        const mismatched = entries
            .filter((entry) => entry.group !== null)
            .filter((entry) => groupsById.get(entry.group ?? "")?.route !== entry.canonicalRoute);

        expect(mismatched.map((entry) => entry.id)).toEqual([]);
    });

    it("sends every group-less entry to the settings home or to a shipped route outside settings", () => {
        const stranded = entries
            .filter((entry) => entry.group === null)
            .filter(
                (entry) =>
                    entry.canonicalRoute !== SETTINGS_HOME_ROUTE &&
                    !(SHIPPED_APP_ROUTES as readonly string[]).includes(entry.canonicalRoute),
            );

        expect(stranded.map((entry) => entry.id)).toEqual([]);
    });

    it("claims every scope group in the target navigation", () => {
        const claimed = new Set(entries.map((entry) => entry.canonicalRoute));

        expect(groups.filter((group) => !claimed.has(group.route)).map((group) => group.id)).toEqual([]);
    });

    it("names a redirect target for every redirect and none for a rendered destination", () => {
        const wrong = entries.filter((entry) =>
            entry.kind === "destination" ? entry.redirectsTo !== null : entry.redirectsTo === null,
        );

        expect(wrong.map((entry) => entry.id)).toEqual([]);
    });
});

describe("settings manifest covers the routed settings surface", () => {
    it("registers every routed page under the settings roots", () => {
        const unregistered = diskRoutes.filter((route) => !registeredRoutes.has(route));

        expect(
            unregistered,
            `${unregistered.length} settings page(s) exist but are not in the manifest. For each one, ${REGISTER_ENTRY}.`,
        ).toEqual([]);
    });

    it("scans a settings surface large enough to be meaningful", () => {
        expect(diskRoutes.length).toBeGreaterThan(30);
    });

    it("registers no current route the app does not serve", () => {
        const missing = entries.filter(
            (entry) => !existsSync(path.join(APP_DIRECTORY, entry.currentRoute, "page.tsx")),
        );

        expect(
            missing.map((entry) => `${entry.id} -> ${entry.currentRoute}`),
            "a manifest entry names a route with no page.tsx; move the entry or delete it in the same commit as the route",
        ).toEqual([]);
    });
});

describe("settings manifest labels resolve in both locales", () => {
    it("resolves every entry title and palette alias key in English and Japanese", () => {
        const keys = entries.flatMap((entry) =>
            [entry.titleKey, entry.aliasKey].filter((key): key is string => key !== null),
        );
        const unresolved = keys.filter(
            (key) => resolveMessage(english, key) === null || resolveMessage(japanese, key) === null,
        );

        expect(unresolved).toEqual([]);
    });

    it("resolves every reused group title key in English and Japanese", () => {
        const keys = groups.map((group) => group.titleKey).filter(
            (key): key is string => key !== null,
        );
        const unresolved = keys.filter(
            (key) => resolveMessage(english, key) === null || resolveMessage(japanese, key) === null,
        );

        expect(unresolved).toEqual([]);
    });

    it("reuses a group title key only when the shipped English already reads as the group's name", () => {
        const drifted = groups.filter((group) => group.titleKey !== null).filter(
            (group) => resolveMessage(english, group.titleKey ?? "") !== group.epicName,
        );

        expect(
            drifted.map((group) => group.id),
            "a group either reuses a key whose rendered label already matches, or carries null so the shell PR authors one; it never silently renames a shipped destination",
        ).toEqual([]);
    });

    it("names every group the epic names, exactly once per scope", () => {
        expect(groups.map((group) => group.epicName)).toHaveLength(16);
        expect(groups.filter((group) => group.scope === "personal")).toHaveLength(5);
        expect(groups.filter((group) => group.scope === "workspace")).toHaveLength(6);
        expect(groups.filter((group) => group.scope === "organization")).toHaveLength(5);
    });
});

describe("settings manifest names real authorization", () => {
    it("reads a backend permission enum large enough to be meaningful", () => {
        expect(permissions.size).toBeGreaterThan(30);
        expect(permissions.has("WORKSPACE_SETTINGS")).toBe(true);
    });

    it("names only permissions the backend declares", () => {
        const unknown = entries.flatMap((entry) =>
            [...entry.access.permissions, ...entry.access.manage]
                .filter((permission) => !permissions.has(permission))
                .map((permission) => `${entry.id}: ${permission}`),
        );

        expect(
            unknown,
            "a manifest entry names a permission the backend Permission enum does not declare",
        ).toEqual([]);
    });

    it("declares the in-place states its own gates imply", () => {
        const inconsistent = entries.flatMap((entry) => {
            const { permissions: required, capabilities, orgAdmin, states } = entry.access;
            const gated = required.length > 0 || capabilities.length > 0 || orgAdmin;
            const problems: string[] = [];
            if (!gated && states.length > 0) {
                problems.push(`${entry.id} declares states but no gate that could produce them`);
            }
            if ((required.length > 0 || orgAdmin) && !states.includes("ask-admin")) {
                problems.push(`${entry.id} is permission-gated but never says to ask an administrator`);
            }
            if (gated && !states.includes("retry")) {
                problems.push(`${entry.id} is gated but never explains a failed lookup`);
            }
            if (
                capabilities.length > 0 &&
                !states.includes("managed") &&
                !states.includes("not-enabled")
            ) {
                problems.push(`${entry.id} depends on a capability but explains neither managed nor disabled`);
            }
            return problems;
        });

        expect(inconsistent).toEqual([]);
    });
});

describe("settings manifest owns each canonical destination once", () => {
    it("lets no two destinations own the same canonical route", () => {
        const owners = entries
            .filter((entry) => entry.kind === "destination" && entry.canonicalSection === null)
            .map((entry) => entry.canonicalRoute);

        expect(owners, "two destinations claim the same canonical route; one of them is a section of the other").toEqual(
            [...new Set(owners)],
        );
    });

    it("lets no two destinations claim the same section of a shared route", () => {
        const deepLinks = entries
            .filter((entry) => entry.kind === "destination")
            .map((entry) => `${entry.canonicalRoute}#${entry.canonicalSection ?? ""}`);

        expect(deepLinks).toEqual([...new Set(deepLinks)]);
    });
});

describe("settings manifest agrees with the navigation that links into settings", () => {
    it("finds every declared registry entry point in the source that registers it", () => {
        const missing = entries.flatMap((entry) =>
            entry.entryPoints
                .filter((point): point is Exclude<SettingsEntryPoint, "contextual"> => point !== "contextual")
                .filter(
                    (point) =>
                        !ENTRY_POINT_SOURCES[point].some((file) =>
                            linkedRoutes(file).includes(entry.currentRoute),
                        ),
                )
                .map((point) => `${entry.id} claims ${point} but no such registration links ${entry.currentRoute}`),
        );

        expect(missing).toEqual([]);
    });

    it("registers every settings destination the navigation sources link to", () => {
        const unregistered = Object.entries(ENTRY_POINT_SOURCES).flatMap(([point, files]) =>
            [...new Set(files.flatMap(linkedRoutes))]
                .filter(underSettingsRoot)
                .filter((route) => !registeredRoutes.has(route))
                .map((route) => `${point} links ${route}`),
        );

        expect(
            unregistered,
            `${unregistered.length} navigation link(s) point at an unregistered settings destination. For each one, ${REGISTER_ENTRY}.`,
        ).toEqual([]);
    });

    it("scans navigation sources that actually carry settings links", () => {
        const linked = Object.values(ENTRY_POINT_SOURCES)
            .flat()
            .flatMap(linkedRoutes)
            .filter(underSettingsRoot);

        expect(new Set(linked).size).toBeGreaterThan(20);
    });
});

describe("settings manifest covers the surface #1340 committed as the acceptance denominator", () => {
    it("registers every destination the epic's consolidation scope names", () => {
        const required = [
            "/account/connections",
            "/account/connections/reviews",
            "/account/invites",
            "/account/notifications",
            "/account/profile",
            "/account/security",
            "/admin/logs",
            "/organization/ai",
            "/organization/allowed-domains",
            "/organization/audit",
            "/organization/data-requests",
            "/organization/diagnostics",
            "/organization/members",
            "/organization/overview",
            "/organization/sso",
            "/records/approval-policies",
            "/settings/custom-fields",
            "/settings/data",
            "/settings/delivery",
            "/settings/diagnostics",
            "/settings/email",
            "/settings/general",
            "/settings/members",
            "/settings/qualification",
            "/settings/roles",
            "/settings/rules",
            "/users",
        ];

        expect(required.filter((route) => !registeredRoutes.has(route))).toEqual([]);
    });
});
