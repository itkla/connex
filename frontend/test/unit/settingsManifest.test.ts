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
import {
    settingsDestinationHref,
    settingsEntryPointDestinations,
    settingsRouteServed,
} from "@/app/lib/settingsEntryPoints";
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
 *   `settingsNavigation.test.ts` — leaving a literal href added there to bypass both gates. Shell
 *   files are now scanned as navigation sources that may declare no entry point at all, so a
 *   hand-written settings link in one of them fails rather than passing unseen. They are
 *   **discovered, not listed**: a shell file is one that imports the manifest-resolved navigation,
 *   so the shell files #1340 PR 8 adds are scanned without anyone remembering to extend a roster.
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
 * Two strips remain, and each survives because routes it links still render. `OrgTabs` does not:
 * #1340 PR 8 turned every `/organization/*` address into a redirect, which left the strip with
 * nothing to link and its layout with nothing to wrap, so both were deleted and the
 * `organization-tabs` entry point went with them.
 *
 * The other two are held rather than kept. `AccountTabs` links five personal destinations whose
 * canonical `/settings/personal/*` routes were never built, and `SettingsTabs` links the two
 * workspace destinations — General and Data & privacy — in the same position. Retiring either
 * strip before its destinations move would strand pages that still serve. What retires them is the
 * personal scope and the two remaining workspace groups shipping, not another pass over this list.
 *
 * Every other surface names a manifest entry instead and is verified structurally below. The shell
 * files declare no entry point at all: they render from the manifest at request time, so a literal
 * settings href appearing in one of them is a hand-written link that has escaped the manifest, and
 * the reverse-direction gate fails on it.
 */
const TAB_STRIP_SOURCES: ReadonlyArray<{
    file: string;
    points: readonly Exclude<SettingsEntryPoint, "contextual">[];
}> = [
    { file: "app/components/account/AccountTabs.tsx", points: ["account-tabs"] },
    { file: "app/components/settings/SettingsTabs.tsx", points: ["settings-tabs"] },
];

/** The import that makes a file part of the settings shell. */
const SHELL_IMPORT = "@/app/lib/settingsNavigation";

/** Whether a source file renders the manifest-resolved settings navigation. */
function isShellSource(source: string): boolean {
    return source.includes(SHELL_IMPORT);
}

/** Every `.ts`/`.tsx` file under a directory. */
function sourceFilesUnder(directory: string, prefix: string): string[] {
    const files: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const next = path.join(directory, entry.name);
        if (entry.isDirectory()) files.push(...sourceFilesUnder(next, `${prefix}/${entry.name}`));
        else if (/\.tsx?$/.test(entry.name)) files.push(`${prefix}/${entry.name}`);
    }
    return files;
}

/**
 * The settings shell, discovered rather than listed.
 *
 * A shell file is one that renders the manifest-resolved navigation — it imports
 * {@link SHELL_IMPORT} — which is a fact about what the file does, not about what it is called or
 * where it sits. #1340 PR 8 adds shell files; each is scanned the moment it starts consuming the
 * navigation, with no list here to remember to extend.
 */
function shellSources(): string[] {
    return sourceFilesUnder(path.join(process.cwd(), "app"), "app")
        .filter((file) => isShellSource(readSource(file)))
        .sort();
}

const SHELL_SOURCES = shellSources();

const NAVIGATION_SOURCES: ReadonlyArray<{
    file: string;
    points: readonly Exclude<SettingsEntryPoint, "contextual">[];
}> = [
    ...TAB_STRIP_SOURCES,
    ...SHELL_SOURCES.map((file) => ({ file, points: [] as const })),
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

/**
 * The route half of a redirect target.
 *
 * A fragment addresses a section of a page, not a page; the router never sees it. Resolving the
 * whole string against the shipped routes would fail every consolidated redirect the moment it
 * started carrying the section that preserves the reader's intent, so the two halves are checked
 * apart: the route against what the app serves, the fragment against the entry's own
 * `canonicalSection` in {@link strayFragmentRedirects}.
 */
function targetRoute(target: string): string {
    return target.split("#")[0];
}

/** Redirects naming a route the app does not serve. */
function danglingRedirects(candidates: readonly SettingsEntry[]): readonly string[] {
    return candidates
        .filter((entry) => entry.redirectsTo !== null)
        .filter((entry) => {
            const route = targetRoute(entry.redirectsTo ?? "");
            return (
                !registeredRoutes.has(route)
                && !(SHIPPED_APP_ROUTES as readonly string[]).includes(route)
            );
        })
        .map((entry) => `${entry.id} -> ${entry.redirectsTo}`);
}

/**
 * Redirects that forward somewhere other than their canonical destination, once that destination is
 * served.
 *
 * This is what makes the redirect matrix the manifest's rather than a table someone maintains
 * beside it. A job whose consolidated home has shipped must forward to exactly that home, at
 * exactly the section that absorbed it — `settingsDestinationHref` composes that address for the
 * navigation, and a redirect that resolved it differently would send a bookmark somewhere the
 * sidebar does not.
 *
 * Silent where the canonical route has not shipped, which is the personal scope and the two
 * workspace groups still waiting on one. Those entries forward to whatever address works today, and
 * the only claim that can honestly be made about them is the dangling check above.
 */
function offCanonicalRedirects(candidates: readonly SettingsEntry[]): readonly string[] {
    return candidates
        .filter((entry) => entry.redirectsTo !== null)
        .filter((entry) => settingsRouteServed(entry.canonicalRoute))
        .filter((entry) => entry.redirectsTo !== settingsDestinationHref(entry))
        .map(
            (entry) =>
                `${entry.id} -> ${entry.redirectsTo} (canonical ${settingsDestinationHref(entry) ?? "none"})`,
        );
}

/**
 * Redirects whose target is itself an address that redirects.
 *
 * A permanent redirect is a promise about where something lives, and a chain makes that promise
 * twice — costing a round trip, and rotting the moment the middle hop is retired. `/settings/sso`
 * is the case this was written for: it forwarded to `/organization/sso`, which #1340 PR 8 turned
 * into a forward of its own, so it was retargeted at the section both of them mean.
 */
function chainedRedirects(candidates: readonly SettingsEntry[]): readonly string[] {
    const forwarding = new Set(
        candidates.filter((entry) => entry.redirectsTo !== null).map((entry) => entry.currentRoute),
    );
    return candidates
        .filter((entry) => entry.redirectsTo !== null)
        .filter((entry) => forwarding.has(targetRoute(entry.redirectsTo ?? "")))
        .map((entry) => `${entry.id} -> ${entry.redirectsTo}`);
}

/** Redirects carrying a fragment that is not the entry's own canonical section. */
function strayFragmentRedirects(candidates: readonly SettingsEntry[]): readonly string[] {
    return candidates
        .filter((entry) => entry.redirectsTo !== null)
        .filter((entry) => {
            const fragment = (entry.redirectsTo ?? "").split("#")[1];
            return fragment !== undefined && fragment !== entry.canonicalSection;
        })
        .map((entry) => `${entry.id} -> ${entry.redirectsTo}`);
}

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
        expect(
            danglingRedirects(entries),
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

    it("forwards each retired address to its canonical destination and section", () => {
        expect(
            offCanonicalRedirects(entries),
            "a job whose consolidated home has shipped forwards to that home at the section that absorbed it, which is the address the navigation resolves for the same entry",
        ).toEqual([]);
    });

    it("resolves every redirect in one hop", () => {
        expect(
            chainedRedirects(entries),
            "a redirect names a target that redirects again; point it at the final destination",
        ).toEqual([]);
    });

    it("carries only a section its own entry claims", () => {
        expect(
            strayFragmentRedirects(entries),
            "a redirect target's fragment must be the entry's canonicalSection, or the deep link and the manifest disagree about what the reader asked for",
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

describe("the redirect gates refuse a matrix that has drifted from the manifest", () => {
    const retired = SETTINGS_ENTRIES.find((entry) => entry.id === "workspace.members");
    const held = SETTINGS_ENTRIES.find((entry) => entry.id === "legacy.settings-security");

    function withRedirect(redirectsTo: string): readonly SettingsEntry[] {
        if (!retired) throw new Error("workspace.members is the template for these probes");
        return [{ ...retired, id: "test.redirect", redirectsTo }];
    }

    it("catches a redirect that stops at the page and drops the section it named", () => {
        expect(offCanonicalRedirects(withRedirect("/settings/workspace/people"))).toEqual([
            "test.redirect -> /settings/workspace/people (canonical /settings/workspace/people#members)",
        ]);
    });

    it("catches a redirect aimed at a different served destination", () => {
        expect(
            offCanonicalRedirects(withRedirect("/settings/workspace/crm#custom-fields")).length,
        ).toBe(1);
    });

    it("stays silent for a job whose canonical destination has not shipped", () => {
        if (!held) throw new Error("legacy.settings-security is the template for this probe");

        expect(
            settingsRouteServed(held.canonicalRoute),
            "this expectation is what makes the assertion below meaningful; it inverts when /settings/personal/security ships",
        ).toBe(false);
        expect(offCanonicalRedirects([held])).toEqual([]);
    });

    it("catches a fragment the entry never claimed", () => {
        expect(strayFragmentRedirects(withRedirect("/settings/workspace/people#roles"))).toEqual([
            "test.redirect -> /settings/workspace/people#roles",
        ]);
        expect(strayFragmentRedirects(withRedirect("/settings/workspace/people#members"))).toEqual([]);
    });

    it("catches a redirect onto an address that redirects again", () => {
        if (!retired) throw new Error("workspace.members is the template for these probes");
        const chained: SettingsEntry = { ...retired, id: "test.chained", redirectsTo: "/settings/roles" };

        expect(chainedRedirects([...entries, chained])).toEqual([
            "test.chained -> /settings/roles",
        ]);
    });

    it("resolves a target's route without letting its fragment dangle it", () => {
        expect(danglingRedirects(withRedirect("/settings/workspace/people#members"))).toEqual([]);
        expect(danglingRedirects(withRedirect("/settings/workspace/nowhere#members"))).toEqual([
            "test.redirect -> /settings/workspace/nowhere#members",
        ]);
    });
});

describe("every retired address forwards from the manifest rather than from a route in a stub", () => {
    /**
     * The forwards whose target is a fixed address the manifest can hand over.
     *
     * Two are not, and both are excluded here and enumerated below: one resolves a legacy
     * automation id into a workflow id, and one resolves which provider's review queue the reader
     * actually has. A target computed from a lookup cannot be read from a static field, so those
     * stubs compose their own and are checked by their own suites instead.
     */
    const computed = new Set(["account.capture-reviews", "legacy.settings-workflow"]);
    const stubs = entries.filter(
        (entry) =>
            entry.redirectsTo !== null
            && !entry.currentRoute.includes("[")
            && !computed.has(entry.id),
    );

    function stubSource(entry: SettingsEntry): string {
        return readSource(path.join("app", "(app)", entry.currentRoute, "page.tsx"));
    }

    it("covers every retired address the manifest declares", () => {
        expect(stubs.length).toBeGreaterThan(20);
    });

    it("names its manifest entry and redirects permanently", () => {
        const wrong = stubs
            .filter(
                (entry) =>
                    !stubSource(entry).includes(`settingsRedirectTarget("${entry.id}"`)
                    || !stubSource(entry).includes("permanentRedirect("),
            )
            .map((entry) => entry.currentRoute);

        expect(
            wrong,
            "a redirect stub resolves its target by naming its manifest entry, so a destination that moves takes its redirects with it; and it forwards permanently, because the address is retired rather than busy",
        ).toEqual([]);
    });

    it("spells no destination route of its own", () => {
        const literal = stubs
            .filter((entry) => stubSource(entry).includes(targetRoute(entry.redirectsTo ?? "")))
            .map((entry) => entry.currentRoute);

        expect(
            literal,
            "a stub that spells its target is a second redirect matrix; the manifest is the only one",
        ).toEqual([]);
    });

    /**
     * Read out of the stubs rather than restated, so the exemption cannot be widened by editing the
     * list. A stub that stops resolving its address from the manifest appears here whether or not
     * anyone remembered to declare it, and the assertion fails until the exemption is argued.
     */
    it("exempts only the forwards whose target has to be looked up first", () => {
        const notManifestDriven = entries
            .filter((entry) => entry.redirectsTo !== null)
            .filter((entry) => !stubSource(entry).includes("settingsRedirectTarget("))
            .map((entry) => entry.id)
            .sort();

        expect(
            notManifestDriven,
            "a forward whose target depends on a lookup cannot read a fixed address from the manifest; every other one must, so that a destination which moves takes its redirects with it",
        ).toEqual([...computed].sort());
    });

    it("holds each exempted forward to redirecting anyway", () => {
        const notRedirecting = [...computed]
            .map((id) => entries.find((entry) => entry.id === id))
            .filter((entry): entry is SettingsEntry => entry !== undefined)
            .filter((entry) => !/\b(?:permanentRedirect|redirect)\(/.test(stubSource(entry)))
            .map((entry) => entry.currentRoute);

        expect(notRedirecting).toEqual([]);
    });
});

describe("the member profile is held rather than redirected", () => {
    it("keeps /users/[id] serving until member-detail can take an id", () => {
        const detail = entriesByRoute.get("/users/[id]");
        if (!detail) throw new Error("workspace.people-detail left the manifest");

        expect(
            [detail.kind, detail.redirectsTo],
            "redirecting /users/42 at #member-detail would drop the 42: the section takes no id and sits on the directory list. Read the decision recorded on the entry before changing this.",
        ).toEqual(["destination", null]);
        expect(existsSync(path.join(APP_DIRECTORY, "users", "[id]", "page.tsx"))).toBe(true);
    });

    it("still redirects the directory the profile is reached from", () => {
        const directory = entriesByRoute.get("/users");

        expect(directory?.redirectsTo).toBe("/settings/workspace/people#directory");
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

    /**
     * The same property over every entry, not just the rendering ones.
     *
     * Restricting the check above to destinations was honest while the absorbed jobs still rendered;
     * #1340 PR 8 turned most of them into redirects, which would have left it inspecting a handful
     * of canonical pages and calling the manifest unambiguous. Widened, it stops being a uniqueness
     * assertion — legacy aliases legitimately collapse onto the address they alias — and becomes an
     * inventory of exactly which addresses do, so an accidental collision has to be added here to
     * pass rather than disappearing into a set that was always going to be unique.
     *
     * Each pair below is one canonical address reached by two legacy names. Five are the personal
     * scope's `/settings/*` aliases for `/account/*`, which stay until the personal routes ship; the
     * sixth is `/organization` and the group route it now forwards to.
     */
    it("enumerates every canonical address that more than one entry resolves to", () => {
        const byLink = new Map<string, string[]>();
        for (const entry of entries) {
            const link = `${entry.canonicalRoute}#${entry.canonicalSection ?? ""}`;
            byLink.set(link, [...(byLink.get(link) ?? []), entry.id]);
        }
        const collapsed = [...byLink.entries()]
            .filter(([, ids]) => ids.length > 1)
            .map(([link, ids]) => `${link} <- ${ids.sort().join(", ")}`)
            .sort();

        expect(collapsed).toEqual([
            "/settings/organization/general# <- organization.general, organization.home",
            "/settings/organization/identity#sso <- legacy.settings-sso, organization.sso",
            "/settings/personal/notifications# <- account.notifications, legacy.settings-notifications",
            "/settings/personal/profile# <- account.home, account.profile",
            "/settings/personal/security# <- account.security, legacy.settings-security",
            "/settings/personal/workspaces# <- account.invites, legacy.settings-membership",
        ]);
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

    /**
     * A canary over the scan itself, not over the manifest: it fails if the reconciliation above
     * ever runs against nothing and passes vacuously.
     *
     * Lowered from 20 in #1340 PR 8, and only because the surface it counts genuinely shrank. The
     * organization tab strip was deleted and the workspace strip fell from nine links to two, which
     * is the point of the PR — those destinations are now reached through the manifest-resolved
     * navigation rather than spelled on a strip. The mark tracks the surface down; it must never be
     * lowered to accommodate a scan that stopped finding files it should still be reading.
     */
    it("scans navigation sources that actually carry settings links", () => {
        const linked = NAVIGATION_SOURCES.flatMap((source) => linkedRoutes(source.file)).filter(
            underSettingsRoot,
        );
        const named = GENERATED_SOURCES.flatMap((source) => source.registered());

        expect(new Set([...linked, ...named]).size).toBeGreaterThan(12);
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
                .filter((href) => href === null || !registeredRoutes.has(href.split("#")[0]))
                .map((href) => `${source.describe} resolves to ${href ?? "no address"}`),
        );

        expect(stranded).toEqual([]);
    });

    it("hands out no address for a route pattern, and keeps that entry off the resolver", () => {
        const detail = entriesByRoute.get("/users/[id]");
        if (!detail) throw new Error("workspace.people-detail left the manifest");

        expect(
            settingsDestinationHref(detail),
            "a parameterized route is a pattern, not an address; resolving it would ship a link to the literal /users/[id]",
        ).toBeNull();
        expect(
            settingsEntryPointDestinations("contextual").map((destination) => destination.id),
        ).not.toContain(detail.id);
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

    /**
     * An inventory, not the gate. Discovery is what scans a new shell file; this makes the shell's
     * growth visible in review, so a file that starts rendering the settings navigation is noticed
     * rather than merely tolerated.
     */
    it("holds the settings shell at the files that render the navigation today", () => {
        expect(SHELL_SOURCES).toEqual([
            "app/components/settings/SettingsDirectory.tsx",
            "app/components/settings/SettingsDrillDown.tsx",
            "app/components/settings/SettingsHome.tsx",
            "app/components/settings/SettingsScopeSpine.tsx",
            "app/components/settings/SettingsSearchResults.tsx",
        ]);
        expect(
            NAVIGATION_SOURCES.filter((source) => source.points.length === 0).map((source) => source.file),
        ).toEqual(SHELL_SOURCES);
    });

    it("catches a shell file that does not exist yet", () => {
        expect(
            isShellSource(`import { resolveSettingsNavigation } from "${SHELL_IMPORT}";`),
            "a file rendering the manifest navigation is a shell file wherever it is added and whatever it is called",
        ).toBe(true);
        expect(isShellSource('import { SETTINGS_ENTRIES } from "@/app/lib/settingsManifest";')).toBe(false);
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
