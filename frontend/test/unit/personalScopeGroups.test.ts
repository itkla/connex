import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    CONNECTED_ACCOUNTS_ROUTE,
    CONNECTED_ACCOUNTS_SECTIONS,
    MANIFEST_CONNECTED_ACCOUNTS_SECTIONS,
    connectedAccountsSectionHref,
} from "@/app/lib/connectedAccountsSections";
import { settingsRouteServed } from "@/app/lib/settingsEntryPoints";
import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsEntry,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/**
 * Gate over the seven destinations that finish #1340 — the five personal scope groups, and the two
 * workspace groups the earlier PRs left behind.
 *
 * These are the groups the eight-PR plan never built. PR 8 could not retire their legacy addresses,
 * because forwarding them would have 404ed, so it held `AccountTabs` and a trimmed `SettingsTabs`
 * alive over the seven pages that still served. What this suite holds is that both halves of that
 * hold are now discharged together: each group serves its canonical route, and each legacy address
 * forwards to it rather than rendering a second copy of the same panel.
 *
 * Most of these groups are one section, so there is far less to say about anchors than there was for
 * the consolidated workspace destinations. Connected accounts is the exception and is checked in
 * full: it absorbed a job that had its own address.
 *
 * What this deliberately does not assert: how the pages look, whether the copy reads well, or
 * whether an arrival lands where a reader expected. Those are the browser pass's job.
 */
const APP = path.join(process.cwd(), "app");
const COMPONENTS = path.join(APP, "components", "settings");

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

type FinalGroup = {
    id: string;
    /** The composition component the route renders. */
    view: string;
    /** The message key the page's own heading resolves. */
    titleKey: string;
    /** The addresses this group retired, each of which must now forward to it. */
    retired: readonly string[];
};

const GROUPS: readonly FinalGroup[] = [
    {
        id: "personal.profile",
        view: path.join(COMPONENTS, "PersonalProfile.tsx"),
        titleKey: "Account.tabProfile",
        retired: ["/account", "/account/profile"],
    },
    {
        id: "personal.security",
        view: path.join(COMPONENTS, "PersonalSecurity.tsx"),
        titleKey: "Account.tabSecurity",
        retired: ["/account/security", "/settings/security"],
    },
    {
        id: "personal.connected-accounts",
        view: path.join(COMPONENTS, "PersonalConnectedAccounts.tsx"),
        titleKey: "AccountConnections.title",
        retired: ["/account/connections", "/account/connections/reviews"],
    },
    {
        id: "personal.notifications",
        view: path.join(COMPONENTS, "PersonalNotifications.tsx"),
        titleKey: "SettingsNav.groupNotificationPreferences",
        retired: ["/account/notifications", "/settings/notifications"],
    },
    {
        id: "personal.workspaces",
        view: path.join(COMPONENTS, "PersonalWorkspaces.tsx"),
        titleKey: "SettingsNav.groupWorkspacesInvitations",
        retired: ["/account/invites", "/settings/membership"],
    },
    {
        id: "workspace.general",
        view: path.join(COMPONENTS, "WorkspaceGeneral.tsx"),
        titleKey: "WorkspaceSettings.tabGeneral",
        retired: ["/settings/general"],
    },
    {
        id: "workspace.data-privacy",
        view: path.join(COMPONENTS, "WorkspaceDataPrivacy.tsx"),
        titleKey: "SettingsNav.groupDataPrivacy",
        retired: ["/settings/data"],
    },
];

const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;
const ENTRIES: readonly SettingsEntry[] = SETTINGS_ENTRIES;

function group(id: string): SettingsGroup {
    const found = MANIFEST_GROUPS.find((candidate) => candidate.id === id);
    if (!found) throw new Error(`no manifest group ${id}`);
    return found;
}

function entryByRoute(route: string): SettingsEntry {
    const found = ENTRIES.find((candidate) => candidate.currentRoute === route);
    if (!found) throw new Error(`no manifest entry serving ${route}`);
    return found;
}

describe("each remaining scope group serves the route the manifest gives it", () => {
    it.each(GROUPS)("$id owns its canonical route outright", (final) => {
        const manifestGroup = group(final.id);
        const entry = entryByRoute(manifestGroup.route);

        expect(existsSync(path.join(routeDir(manifestGroup.route), "page.tsx"))).toBe(true);
        expect(settingsRouteServed(manifestGroup.route)).toBe(true);
        expect(entry.kind).toBe("destination");
        expect(entry.group).toBe(final.id);
        expect(
            entry.canonicalRoute,
            "a destination that owns its group's route is its own canonical owner",
        ).toBe(manifestGroup.route);
        expect(entry.canonicalSection).toBeNull();
        expect(entry.redirectsTo).toBeNull();
        expect(entry.titleKey).toBe(final.titleKey);
    });

    it.each(GROUPS)("$id names itself with the key the navigation labels its group with", (final) => {
        const [namespace, key] = final.titleKey.split(".");
        const view = source(final.view);

        expect(view, "the page heading and the navigation row read from one key").toContain(
            `("${key}")`,
        );
        expect(view).toContain(`useTranslations("${namespace}")`);
        expect(view, "and it is the page title, not a section heading").toContain("<PageHeader");
    });

    it.each(GROUPS)("$id draws a loading skeleton for the shape it becomes", (final) => {
        const skeleton = source(path.join(routeDir(group(final.id).route), "loading.tsx"));

        expect(skeleton).toContain("@/components/ui/skeleton");
        expect(
            skeleton,
            "the settings layout owns the shell; a second one makes the page jump on first paint",
        ).not.toContain("<PageShell");
    });

    it("covers every group the epic names, with nothing left unserved", () => {
        const unserved = MANIFEST_GROUPS.filter((candidate) => !settingsRouteServed(candidate.route));

        expect(MANIFEST_GROUPS.length, "five personal, six workspace, five organization").toBe(16);
        expect(
            unserved.map((candidate) => candidate.id),
            "#1340's one canonical Settings experience is only true when every group has a page",
        ).toEqual([]);
    });
});

describe("every address these groups retired forwards to them", () => {
    it.each(GROUPS.flatMap((final) => final.retired.map((route) => ({ id: final.id, route }))))(
        "$route forwards into $id",
        ({ id, route }) => {
            const entry = entryByRoute(route);

            expect(entry.kind).not.toBe("destination");
            expect(entry.redirectsTo).not.toBeNull();
            expect(
                entry.redirectsTo?.split("#")[0],
                "a retired address lands on the destination that absorbed its job",
            ).toBe(group(id).route);
        },
    );

    /**
     * The seven addresses this PR retired, as opposed to the ones earlier PRs had already pointed at
     * a page that has since moved. Each renders nothing now: a panel left behind would be a second
     * copy of a section, served from an address the navigation no longer offers.
     */
    it.each([
        "/account/connections",
        "/account/invites",
        "/account/notifications",
        "/account/profile",
        "/account/security",
        "/settings/data",
        "/settings/general",
    ])("%s became a stub that renders nothing and spells no destination", (route) => {
        const entry = entryByRoute(route);
        const page = source(path.join(routeDir(route), "page.tsx"));

        expect(page).toContain("permanentRedirect(");
        expect(page).toContain(`settingsRedirectTarget("${entry.id}"`);
        expect(page, "a panel left here would be a second copy of the destination").not.toContain(
            "Panel",
        );
        expect(
            page,
            "a stub that spells its target is a second redirect matrix; the manifest is the only one",
        ).not.toContain(entry.redirectsTo ?? "");
        expect(
            existsSync(path.join(routeDir(route), "loading.tsx")),
            "an address that renders nothing has nothing to draw a skeleton for",
        ).toBe(false);
    });

    it("holds a ledger entry explaining every skeleton it deleted", () => {
        const ledger: unknown = JSON.parse(source(path.join(process.cwd(), "lint", "loading-skeleton-exceptions.json")));
        if (typeof ledger !== "object" || ledger === null || !("exceptions" in ledger)) {
            throw new Error("the skeleton ledger changed shape");
        }
        const { exceptions } = ledger;
        if (!Array.isArray(exceptions)) throw new Error("the skeleton ledger changed shape");
        const routes = new Set(
            exceptions.map((entry: unknown) =>
                typeof entry === "object" && entry !== null && "route" in entry ? entry.route : null,
            ),
        );

        for (const route of [
            "/account/connections",
            "/account/invites",
            "/account/notifications",
            "/account/profile",
            "/account/security",
            "/settings/data",
            "/settings/general",
        ]) {
            expect(routes.has(route), `${route} lost its skeleton without a ledger entry`).toBe(true);
        }
    });
});

describe("the last two peer-tab strips dissolved with their destinations", () => {
    it("deletes both strips and the layout chrome that rendered them", () => {
        expect(existsSync(path.join(APP, "components", "account", "AccountTabs.tsx"))).toBe(false);
        expect(existsSync(path.join(COMPONENTS, "SettingsTabs.tsx"))).toBe(false);
        expect(existsSync(path.join(COMPONENTS, "WorkspaceSettingsChrome.tsx"))).toBe(false);
        expect(
            existsSync(path.join(APP, "(app)", "account", "layout.tsx")),
            "the account segment holds nothing but forwards now, so its shell would be assembled on the way to a redirect",
        ).toBe(false);
    });

    /**
     * Imports rather than mentions. The settings layout still names `WorkspaceSettingsChrome` in its
     * docblock, because what used to stand there is the reason the layout now does almost nothing,
     * and a reader arriving at a two-line shell deserves to know it was not always one.
     */
    it("leaves nothing importing them", () => {
        const retired = [
            "@/app/components/account/AccountTabs",
            "@/app/components/settings/SettingsTabs",
            "@/app/components/settings/WorkspaceSettingsChrome",
        ];
        const stragglers = appFiles(APP).filter((file) => {
            const text = source(file);
            return retired.some((module) => text.includes(`from "${module}"`));
        });

        expect(stragglers.map((file) => path.relative(process.cwd(), file))).toEqual([]);
    });

    it("retires their entry points from the manifest's own vocabulary", () => {
        const manifest = source(path.join(APP, "lib", "settingsManifest.ts"));
        const union = manifest.slice(
            manifest.indexOf("export type SettingsEntryPoint"),
            manifest.indexOf("* A boolean instance-capability key"),
        );

        expect(
            union,
            "a variant left behind would let an entry claim a surface that no longer exists",
        ).not.toContain('"account-tabs"');
        expect(union).not.toContain('"settings-tabs"');
        expect(union).not.toContain('"organization-tabs"');
    });
});

describe("connected accounts keeps the one section it absorbed addressable", () => {
    it("holds exactly the sections the manifest declares", () => {
        expect(
            [...CONNECTED_ACCOUNTS_SECTIONS].sort(),
            "absorbed sections and declared route gaps alike, and nothing invented beside them",
        ).toEqual([...MANIFEST_CONNECTED_ACCOUNTS_SECTIONS].sort());
        expect(CONNECTED_ACCOUNTS_ROUTE).toBe(group("personal.connected-accounts").route);
    });

    it("gives the section a real element to arrive at, registered with the arrival hook", () => {
        const view = source(path.join(COMPONENTS, "PersonalConnectedAccounts.tsx"));

        for (const section of CONNECTED_ACCOUNTS_SECTIONS) {
            expect(view).toContain(`id="${section}"`);
            expect(view).toContain(`register("${section}")`);
        }
        expect(view, "a page that does not register cannot be arrived at by fragment").toContain(
            "useSectionArrival(",
        );
    });

    it("builds the deep link from one place, so the anchor cannot be spelled two ways", () => {
        expect(connectedAccountsSectionHref("reviews")).toBe(
            `${CONNECTED_ACCOUNTS_ROUTE}#reviews`,
        );

        /**
         * Origins rather than producers: the builder every consumer asks for an href, and the
         * manifest it reads its slug from, which also records the retired reviews address's forward
         * target — and that target is this same deep link.
         */
        const builders = new Set([
            path.join(APP, "lib", "connectedAccountsSections.ts"),
            path.join(APP, "lib", "settingsManifest.ts"),
        ]);
        const strays = appFiles(APP)
            .filter((file) => !builders.has(file))
            .filter((file) => source(file).includes(`${CONNECTED_ACCOUNTS_ROUTE}#`));

        expect(
            strays.map((file) => path.relative(process.cwd(), file)),
            "route a section deep link through its href builder rather than writing the fragment out",
        ).toEqual([]);
    });

    it("composes the shipped panel without a copy of its state", () => {
        const view = source(path.join(COMPONENTS, "PersonalConnectedAccounts.tsx"));

        expect(view).toContain("<ConnectionsPanel");
        for (const prop of [
            "capabilities={capabilities}",
            "capabilitiesAvailability={capabilitiesAvailability}",
            "effectivePermissions={effectivePermissions}",
            "permissionsStatus={permissionsStatus}",
        ]) {
            expect(view, "the route resolves the contract; the page hands it straight through").toContain(prop);
        }
        expect(
            view,
            "the panel reads and writes the query string, so it needs the boundary its route used to give it",
        ).toContain("<Suspense");
    });

    it("keeps the resolver address forwarding to the destination rather than to its own past", () => {
        const resolver = source(path.join(routeDir("/account/connections/reviews"), "page.tsx"));
        const entry = entryByRoute("/account/connections/reviews");

        expect(entry.redirectsTo).toBe(`${CONNECTED_ACCOUNTS_ROUTE}#reviews`);
        expect(
            resolver,
            "the resolver picks a provider before it forwards, so it reads the route from the section module rather than spelling one",
        ).toContain("CONNECTED_ACCOUNTS_ROUTE");
        expect(resolver).not.toContain('"/account/connections"');
    });
});

describe("the remaining groups gate exactly what their pages need", () => {
    it("leaves every personal destination open to the reader it belongs to", () => {
        for (const id of ["personal.profile", "personal.security", "personal.notifications", "personal.workspaces"]) {
            const entry = entryByRoute(group(id).route);

            expect(
                entry.access.permissions,
                "a person's own settings are not a workspace permission",
            ).toEqual([]);
            expect(entry.access.orgAdmin).toBe(false);
        }
    });

    it("keeps connected accounts visible on a deployment that enables any one provider", () => {
        const entry = entryByRoute(CONNECTED_ACCOUNTS_ROUTE);

        expect(entry.access.capabilityMatch).toBe("any");
        expect(entry.access.capabilities.map((requirement) => requirement.key).sort()).toEqual([
            "connectedAccounts.google",
            "connectedAccounts.microsoft",
            "connectedCapture.google",
            "connectedCapture.microsoft",
        ]);
        expect(
            entry.access.states,
            "a capability-managed destination explains its state in place rather than vanishing",
        ).toContain("not-enabled");
    });

    it("gates workspace General on the permission its endpoint enforces, and Data & privacy on none", () => {
        const general = entryByRoute(group("workspace.general").route);
        const data = entryByRoute(group("workspace.data-privacy").route);

        expect(general.access.permissions).toEqual(["WORKSPACE_SETTINGS"]);
        expect(general.access.states).toContain("ask-admin");
        expect(
            data.access.permissions,
            "the import is gated on what it writes, which refuses in place rather than hiding the category",
        ).toEqual([]);
        expect(data.access.manage.length).toBeGreaterThan(0);
    });
});
