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
import { SETTINGS_PALETTE_REGISTRATIONS } from "@/app/lib/actions/settingsNavigationActions";
import { settingsDestinationHref } from "@/app/lib/settingsEntryPoints";
import { SHIPPED_APP_ROUTES } from "@/app/lib/routeManifest";

/**
 * Gate over the committed settings/navigation manifest (#1340). The manifest is the single source of
 * navigation truth for every settings, account, and administration destination, so this suite proves
 * it still describes the app: every routed page under the settings roots is registered, every
 * registered route still exists, every label key resolves in both locales, every permission it names
 * is a real backend constant, no two destinations claim the same canonical owner, and the navigation
 * surfaces that link into settings agree with the entry points it declares.
 *
 * Two blind spots remain, named rather than implied:
 *
 * - `contextual` entry points are not verified, because a contextual shortcut may be a computed href
 *   anywhere in the app. Every registry surface is verified in both directions.
 * - Whether a permission belongs in `permissions` or in `manage` is not derivable from the manifest.
 *   Each was audited against the shipped panel and the backend service that serves it; only a
 *   self-contradiction (the same permission filed as both on one entry) is caught here.
 * - The group title-drift gate reads English only. A key whose Japanese has drifted from its English
 *   is not detectable, because the manifest records one name per group, not a translation pair.
 *
 * Two that #1340 PR 7 closed:
 *
 * - The sidebar and the user menu live in one file, and the suite used to accept an entry linked
 *   from either as satisfying a claim to either. It now reads each function's own body, so a claim
 *   to `sidebar` is not satisfied by a user-menu registration or the reverse.
 * - The settings shell reads every route it links from this manifest at render time, so it carried
 *   no literal href for the scan to find and was verified only by the data-level suite in
 *   `settingsNavigation.test.ts` — leaving a literal href added there to bypass both gates. The
 *   shell files are now scanned as navigation sources that may declare no entry point at all, so a
 *   hand-written settings link in one of them fails rather than passing unseen.
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

/**
 * Which entry points each navigation source that still spells routes can register.
 *
 * The three tab strips are the last surfaces that carry settings routes as literals; #1340 PR 8
 * retires them. Every other surface names a manifest entry instead and is verified structurally
 * below. The shell files declare no entry point at all: they render from the manifest at request
 * time, so a literal settings href appearing in one of them is a hand-written link that has escaped
 * the manifest, and the reverse-direction gate fails on it.
 */
const NAVIGATION_SOURCES: ReadonlyArray<{
    file: string;
    points: readonly Exclude<SettingsEntryPoint, "contextual">[];
}> = [
    { file: "app/components/account/AccountTabs.tsx", points: ["account-tabs"] },
    { file: "app/components/settings/SettingsTabs.tsx", points: ["settings-tabs"] },
    { file: "app/components/organization/OrgTabs.tsx", points: ["organization-tabs"] },
    { file: "app/components/settings/SettingsHome.tsx", points: [] },
    { file: "app/components/settings/SettingsDirectory.tsx", points: [] },
    { file: "app/components/settings/SettingsDrillDown.tsx", points: [] },
    { file: "app/components/settings/SettingsSearchResults.tsx", points: [] },
    { file: "app/components/settings/SettingsScopeSpine.tsx", points: [] },
];

/**
 * The surfaces that register a settings destination by naming its manifest entry, and the entry
 * point each one satisfies.
 *
 * This is what makes `entryPoints` generated truth rather than an assertion: the sidebar rows, the
 * user-menu items, and the palette actions all resolve their address and their name from
 * `settingsEntryPoints.ts`, so what a surface registers is an entry id and nothing else. Reading
 * those ids back and reconciling them with the manifest closes the loop in both directions — a
 * declaration nobody ships and a registration nobody declared both fail.
 *
 * The sidebar and the user menu are read from their own function bodies rather than from the file
 * they share, which is how a claim to one stops being satisfiable by the other.
 */
const GENERATED_SOURCES: ReadonlyArray<{
    point: Exclude<SettingsEntryPoint, "contextual">;
    describe: string;
    registered: () => readonly string[];
}> = [
    {
        point: "sidebar",
        describe: "useSections in app/components/Sidebar.tsx",
        registered: () => declaredEntryIds(functionBody(SIDEBAR_FILE, "function useSections(")),
    },
    {
        point: "avatar-menu",
        describe: "UserMenu in app/components/Sidebar.tsx",
        registered: () => declaredEntryIds(functionBody(SIDEBAR_FILE, "function UserMenu(")),
    },
    {
        point: "command-palette",
        describe: "SETTINGS_PALETTE_REGISTRATIONS",
        registered: () => SETTINGS_PALETTE_REGISTRATIONS.map((registration) => registration.entryId),
    },
];

const SIDEBAR_FILE = "app/components/Sidebar.tsx";

/** The manifest entry ids a source names through the shared resolver. */
const ENTRY_ID_PATTERN = /settingsDestination\(\s*"([^"]+)"\s*\)/g;

/** The body of one top-level function, so two surfaces sharing a file are read apart. */
function functionBody(file: string, declaration: string): string {
    const source = readSource(file);
    const start = source.indexOf(declaration);
    if (start < 0) throw new Error(`${file} no longer declares ${declaration}`);
    const end = source.indexOf("\n}\n", start);
    if (end < 0) throw new Error(`could not find the end of ${declaration} in ${file}`);
    return source.slice(start, end);
}

/** Every manifest entry id named in a source fragment, deduplicated. */
function declaredEntryIds(source: string): readonly string[] {
    return [...new Set([...source.matchAll(ENTRY_ID_PATTERN)].map((match) => match[1]))];
}

/** The entries whose manifest declaration names a given entry point. */
function declaringEntries(point: SettingsEntryPoint): readonly string[] {
    return entries.filter((entry) => entry.entryPoints.includes(point)).map((entry) => entry.id);
}

/** The sources that can register a given entry point through a literal route. */
function sourcesRegistering(point: Exclude<SettingsEntryPoint, "contextual">): string[] {
    return NAVIGATION_SOURCES.filter((source) => source.points.includes(point)).map(
        (source) => source.file,
    );
}

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

/**
 * The subtrees the manifest claims to cover: the settings roots in full, plus the subtree of every
 * registered destination that lives outside them, so a page added beside a consolidation target is
 * caught without demanding that its whole unrelated root be registered.
 */
function scannedSubtrees(): string[] {
    const roots = new Set<string>(SETTINGS_ROUTE_ROOTS.map((root) => `/${root}`));
    for (const entry of entries) {
        if (!underSettingsRoot(entry.currentRoute)) roots.add(entry.currentRoute);
    }
    return [...roots].sort();
}

/** The routed pages on disk under the subtrees the manifest claims to cover. */
function routedSettingsPages(): string[] {
    const routes = new Set(
        scannedSubtrees().flatMap((root) =>
            appRouterRoutes(path.join(APP_DIRECTORY, root), root.split("/").filter(Boolean)),
        ),
    );
    return [...routes].sort();
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
const entriesByRoute = new Map(entries.map((entry) => [entry.currentRoute, entry]));
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
            "a group either reuses a key whose rendered English already matches its epicName, or updates epicName to match, or carries null so the shell PR authors one; it never silently renames a shipped destination",
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

    it("never files one permission as both a visibility gate and a manage gate on one entry", () => {
        const contradictory = entries.flatMap((entry) =>
            entry.access.permissions
                .filter((permission) => entry.access.manage.includes(permission))
                .map((permission) => `${entry.id}: ${permission}`),
        );

        expect(
            contradictory,
            "a permission either stops the page rendering or only stops its writes; it cannot be both on one page",
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

    it("keeps an any-of permission gate a named consolidation decision, not a default", () => {
        const loose = entries
            .filter((entry) => entry.access.permissionMatch === "any")
            .map((entry) => entry.id);

        expect(
            loose,
            "loosening a destination's gate is how a consolidated page stays reachable by either of the roles it merged; each one is enumerated here",
        ).toEqual(["workspace.audit-diagnostics"]);
    });

    it("loosens a permission gate only where consolidation earned it", () => {
        expect(unearnedLooseGates(entries)).toEqual([]);
    });
});

/**
 * Destinations whose any-of permission gate is not backed by what they consolidated.
 *
 * An any-of gate is only honest on a page that absorbed two independently gated jobs: it must name
 * more than one permission, hold more than one absorbed section, and name no permission that none
 * of those sections actually reads. Anything else is a strict gate wearing a looser name, which
 * would quietly widen who can reach a settings destination.
 */
function unearnedLooseGates(candidates: readonly SettingsEntry[]): readonly string[] {
    return candidates
        .filter((entry) => entry.access.permissionMatch === "any")
        .filter((entry) => {
            const absorbed = candidates.filter(
                (candidate) =>
                    candidate.id !== entry.id
                    && candidate.canonicalRoute === entry.canonicalRoute
                    && candidate.canonicalSection !== null,
            );
            const covered = new Set(absorbed.flatMap((candidate) => candidate.access.permissions));
            return (
                entry.access.permissions.length < 2
                || absorbed.length < 2
                || entry.access.permissions.some((permission) => !covered.has(permission))
            );
        })
        .map((entry) => entry.id);
}

describe("the permission-match gate refuses a loosening it has not earned", () => {
    const template = SETTINGS_ENTRIES.find((entry) => entry.id === "workspace.audit-diagnostics");

    function withLooseGate(access: Partial<SettingsEntry["access"]>): readonly SettingsEntry[] {
        if (!template) throw new Error("workspace.audit-diagnostics is the template for this probe");
        return [
            ...SETTINGS_ENTRIES.filter((entry) => entry.id !== template.id),
            { ...template, id: "test.loose", access: { ...template.access, ...access } },
        ];
    }

    it("catches an any-of gate over a single permission", () => {
        expect(unearnedLooseGates(withLooseGate({ permissions: ["AUDIT_READ"] }))).toEqual([
            "test.loose",
        ]);
    });

    it("catches an any-of gate widened with a permission none of its sections reads", () => {
        expect(
            unearnedLooseGates(
                withLooseGate({ permissions: ["AUDIT_READ", "WORKSPACE_SETTINGS", "DEAL_CREATE"] }),
            ),
        ).toEqual(["test.loose"]);
    });

    it("catches an any-of gate on a destination that consolidated nothing", () => {
        if (!template) throw new Error("workspace.audit-diagnostics is the template for this probe");
        const orphan: SettingsEntry = {
            ...template,
            id: "test.orphan",
            canonicalRoute: "/settings/workspace/nothing",
        };

        expect(unearnedLooseGates([...SETTINGS_ENTRIES, orphan])).toEqual(["test.orphan"]);
    });
});

/** Forwards naming a route the app does not serve. */
function danglingForwards(candidates: readonly SettingsEntry[]): readonly SettingsEntry[] {
    return candidates
        .filter((entry) => entry.conditionalForward !== null)
        .filter(
            (entry) =>
                !registeredRoutes.has(entry.conditionalForward?.to ?? "") &&
                !(SHIPPED_APP_ROUTES as readonly string[]).includes(entry.conditionalForward?.to ?? ""),
        );
}

/** Forwards fired by a capability their own entry declares no requirement on. */
function unbackedForwards(candidates: readonly SettingsEntry[]): readonly SettingsEntry[] {
    return candidates
        .filter((entry) => entry.conditionalForward !== null)
        .filter(
            (entry) =>
                !entry.access.capabilities.some(
                    (requirement) => requirement.key === entry.conditionalForward?.capability,
                ),
        );
}

/**
 * The two gates over `conditionalForward` below hold vacuously: #1340's capability-state work
 * retired `/settings/email`'s and `/organization/sso`'s forwards, and the manifest recorded those
 * two as the complete set, so there is nothing left for them to match. They stay because a future
 * forward must still be caught — the first entry that declares one is checked for a real target and
 * for a capability it actually depends on. The count assertion is what keeps the vacuity honest: it
 * fails if a forward comes back, rather than letting two silent gates imply a check that no longer
 * runs.
 */
describe("settings manifest forwards only to destinations that exist", () => {
    it("declares no capability forward at all (the manifest's set is empty; redirect stubs that resolve addresses are outside this property)", () => {
        const forwards = entries
            .filter((entry) => entry.conditionalForward !== null)
            .map((entry) => `${entry.id} -> ${entry.conditionalForward?.to}`);

        expect(
            forwards,
            "a capability-managed destination explains its state in place; it never forwards the reader somewhere else",
        ).toEqual([]);
    });

    it("resolves every redirect target to a registered route or a shipped route", () => {
        const dangling = entries
            .filter((entry) => entry.redirectsTo !== null)
            .filter(
                (entry) =>
                    !registeredRoutes.has(entry.redirectsTo ?? "") &&
                    !(SHIPPED_APP_ROUTES as readonly string[]).includes(entry.redirectsTo ?? ""),
            );

        expect(
            dangling.map((entry) => `${entry.id} -> ${entry.redirectsTo}`),
            "a redirect names a target the app does not serve",
        ).toEqual([]);
    });

    it("resolves every capability forward to a registered route or a shipped route", () => {
        const dangling = danglingForwards(entries);

        expect(dangling.map((entry) => entry.id)).toEqual([]);
    });

    it("records a capability forward only where a capability the entry declares could fire it", () => {
        const unbacked = unbackedForwards(entries);

        expect(
            unbacked.map((entry) => entry.id),
            "a forward names a capability the entry does not declare a requirement on",
        ).toEqual([]);
    });

    it("still catches a forward, so its two gates are vacuous rather than retired", () => {
        const template = entries.find((entry) => entry.id === "workspace.email");
        if (template === undefined) throw new Error("workspace.email left the manifest");
        const reintroduced: SettingsEntry = {
            ...template,
            id: "test.teleporting",
            conditionalForward: { capability: "mailManaged", expected: true, to: "/settings/members" },
        };
        const nowhere: SettingsEntry = {
            ...reintroduced,
            conditionalForward: { capability: "sso", expected: false, to: "/settings/nowhere" },
        };

        expect(danglingForwards([reintroduced])).toEqual([]);
        expect(danglingForwards([nowhere]).map((entry) => entry.id)).toEqual(["test.teleporting"]);
        expect(unbackedForwards([reintroduced])).toEqual([]);
        expect(unbackedForwards([nowhere]).map((entry) => entry.id)).toEqual(["test.teleporting"]);
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

/** Entry points a source registers that the manifest does not declare there. */
function undeclaredRegistrations(
    point: SettingsEntryPoint,
    registered: readonly string[],
    candidates: readonly SettingsEntry[],
): readonly string[] {
    const declared = new Set(
        candidates.filter((entry) => entry.entryPoints.includes(point)).map((entry) => entry.id),
    );
    return registered.filter((id) => !declared.has(id)).sort();
}

/** Entry points the manifest declares that no source registers. */
function unshippedDeclarations(
    point: SettingsEntryPoint,
    registered: readonly string[],
    candidates: readonly SettingsEntry[],
): readonly string[] {
    const shipped = new Set(registered);
    return candidates
        .filter((entry) => entry.entryPoints.includes(point))
        .map((entry) => entry.id)
        .filter((id) => !shipped.has(id))
        .sort();
}

describe("settings manifest agrees with the navigation that links into settings", () => {
    it("finds every declared registry entry point in the source that registers it", () => {
        const missing = entries.flatMap((entry) =>
            entry.entryPoints
                .filter((point): point is Exclude<SettingsEntryPoint, "contextual"> => point !== "contextual")
                .filter((point) => sourcesRegistering(point).length > 0)
                .filter(
                    (point) =>
                        !sourcesRegistering(point).some((file) =>
                            linkedRoutes(file).includes(entry.currentRoute),
                        ),
                )
                .map((point) => `${entry.id} claims ${point} but no such registration links ${entry.currentRoute}`),
        );

        expect(missing).toEqual([]);
    });

    it("declares an entry point for every registered destination a navigation source links to", () => {
        const undeclared = NAVIGATION_SOURCES.flatMap((source) =>
            [...new Set(linkedRoutes(source.file))]
                .map((route) => entriesByRoute.get(route))
                .filter((entry): entry is SettingsEntry => entry !== undefined)
                .filter((entry) => !entry.entryPoints.some((point) => (source.points as readonly SettingsEntryPoint[]).includes(point)))
                .map((entry) => `${source.file} links ${entry.currentRoute} but ${entry.id} declares none of ${source.points.join(", ") || "no entry point"}`),
        );

        expect(
            undeclared,
            "a navigation surface links a registered destination the manifest does not say links there; add the entry point, or route the link through settingsDestination",
        ).toEqual([]);
    });

    it("registers every settings destination the navigation sources link to", () => {
        const unregistered = NAVIGATION_SOURCES.flatMap((source) =>
            [...new Set(linkedRoutes(source.file))]
                .filter(underSettingsRoot)
                .filter((route) => !registeredRoutes.has(route))
                .map((route) => `${source.file} links ${route}`),
        );

        expect(
            unregistered,
            `${unregistered.length} navigation link(s) point at an unregistered settings destination. For each one, ${REGISTER_ENTRY}.`,
        ).toEqual([]);
    });

    it("scans navigation sources that actually carry settings links", () => {
        const linked = NAVIGATION_SOURCES.flatMap((source) => linkedRoutes(source.file)).filter(
            underSettingsRoot,
        );
        const named = GENERATED_SOURCES.flatMap((source) => source.registered());

        expect(new Set([...linked, ...named]).size).toBeGreaterThan(20);
    });
});

describe("settings manifest entry points are generated from the registrations that ship", () => {
    it.each(GENERATED_SOURCES)(
        "registers in $describe only what the manifest declares for $point",
        (source) => {
            expect(
                undeclaredRegistrations(source.point, source.registered(), entries),
                `${source.describe} registers a destination the manifest does not file under ${source.point}`,
            ).toEqual([]);
        },
    );

    it.each(GENERATED_SOURCES)(
        "ships in $describe every entry point the manifest declares for $point",
        (source) => {
            expect(
                unshippedDeclarations(source.point, source.registered(), entries),
                `the manifest claims ${source.point} for a destination ${source.describe} does not register`,
            ).toEqual([]);
        },
    );

    it("names only labeled manifest entries, so every registration has something to render", () => {
        const unlabeled = GENERATED_SOURCES.flatMap((source) =>
            source
                .registered()
                .filter((id) => entries.find((entry) => entry.id === id)?.titleKey == null)
                .map((id) => `${source.describe} registers ${id}, which names no label`),
        );

        expect(unlabeled).toEqual([]);
    });

    it("spells no settings route literally in a generated source", () => {
        const literals = [...new Set(linkedRoutes(SIDEBAR_FILE))].filter(underSettingsRoot);

        expect(
            literals,
            "a sidebar or user-menu row spells a settings route instead of naming its manifest entry",
        ).toEqual([]);
    });

    it("registers each palette destination once, at the address the manifest resolves", () => {
        const actionIds = SETTINGS_PALETTE_REGISTRATIONS.map((registration) => registration.actionId);
        const drifted = SETTINGS_PALETTE_REGISTRATIONS.filter((registration) => {
            const entry = entriesByRoute.get(
                entries.find((candidate) => candidate.id === registration.entryId)?.currentRoute ?? "",
            );
            return entry === undefined || settingsDestinationHref(entry) !== registration.href;
        });

        expect(actionIds).toEqual([...new Set(actionIds)]);
        expect(drifted.map((registration) => registration.entryId)).toEqual([]);
    });
});

describe("an entry point resolves the canonical address once it is served", () => {
    it("sends a job whose canonical destination ships to that destination's section", () => {
        const directory = entriesByRoute.get("/users");
        const auditLog = entriesByRoute.get("/admin/logs");
        if (!directory || !auditLog) throw new Error("the consolidated jobs left the manifest");

        expect(settingsDestinationHref(directory)).toBe("/settings/workspace/people#directory");
        expect(settingsDestinationHref(auditLog)).toBe(
            "/settings/workspace/audit-diagnostics#audit",
        );
    });

    it("keeps a job whose canonical destination has not shipped on the address that works", () => {
        const account = entriesByRoute.get("/account");
        const reviews = entriesByRoute.get("/account/connections/reviews");
        if (!account || !reviews) throw new Error("the personal-scope jobs left the manifest");

        expect(
            registeredRoutes.has(account.canonicalRoute),
            "this expectation is what makes the two below meaningful; it inverts when /settings/personal/profile ships",
        ).toBe(false);
        expect(settingsDestinationHref(account)).toBe("/account");
        expect(settingsDestinationHref(reviews)).toBe("/account/connections/reviews");
    });

    it("addresses a destination that owns its canonical route without a fragment", () => {
        const home = entriesByRoute.get(SETTINGS_HOME_ROUTE);
        const people = entriesByRoute.get("/settings/workspace/people");
        if (!home || !people) throw new Error("a canonical destination left the manifest");

        expect(settingsDestinationHref(home)).toBe(SETTINGS_HOME_ROUTE);
        expect(settingsDestinationHref(people)).toBe("/settings/workspace/people");
    });

    it("resolves every registered entry point to a route the manifest knows", () => {
        const stranded = GENERATED_SOURCES.flatMap((source) =>
            source
                .registered()
                .map((id) => entries.find((entry) => entry.id === id))
                .filter((entry): entry is SettingsEntry => entry !== undefined)
                .map((entry) => settingsDestinationHref(entry))
                .filter((href) => !registeredRoutes.has(href.split("#")[0]))
                .map((href) => `${source.describe} resolves to ${href}`),
        );

        expect(stranded).toEqual([]);
    });
});

describe("the entry-point gate refuses a registration the manifest has not declared", () => {
    const point: SettingsEntryPoint = "sidebar";
    const declared = declaringEntries(point);

    it("catches a surface registering a destination the manifest files elsewhere", () => {
        expect(undeclaredRegistrations(point, [...declared, "workspace.roles"], entries)).toEqual([
            "workspace.roles",
        ]);
    });

    it("catches a manifest claim no surface ships", () => {
        expect(unshippedDeclarations(point, declared.slice(1), entries)).toEqual([declared[0]].sort());
    });

    it("holds at zero for the registrations that actually ship", () => {
        expect(undeclaredRegistrations(point, declared, entries)).toEqual([]);
        expect(unshippedDeclarations(point, declared, entries)).toEqual([]);
    });

    it("reads the sidebar and the user menu apart, so neither satisfies the other's claim", () => {
        const sidebar = declaredEntryIds(functionBody(SIDEBAR_FILE, "function useSections("));
        const avatar = declaredEntryIds(functionBody(SIDEBAR_FILE, "function UserMenu("));

        expect(sidebar.length).toBeGreaterThan(0);
        expect(avatar.length).toBeGreaterThan(0);
        expect(sidebar.filter((id) => avatar.includes(id))).toEqual([]);
        expect(undeclaredRegistrations("avatar-menu", sidebar, entries)).toEqual(sidebar.slice().sort());
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
