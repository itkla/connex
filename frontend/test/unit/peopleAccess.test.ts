import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    MANIFEST_PEOPLE_SECTIONS,
    PEOPLE_ROUTE,
    PEOPLE_SECTIONS,
    peopleSectionHref,
} from "@/app/lib/peopleSections";
import { SETTINGS_ENTRIES, SETTINGS_GROUPS } from "@/app/lib/settingsManifest";

/**
 * Gate over the consolidated People & access destination (#1340 WS4.3).
 *
 * The epic's acceptance for this workstream is that four scattered surfaces resolve to one page
 * whose sections each keep a stable deep link, and that no permission or tenancy boundary loosened
 * on the way. This suite holds the parts of that a browser pass cannot: that the page's anchors are
 * exactly the sections the manifest promises, that every one of them is spelled once and reached
 * through the shared href builder, that the arrival behaviour a fragment needs is actually wired,
 * and that each panel still renders correctly in the home it had before this page existed.
 *
 * What it deliberately does not assert: how the sections look, and whether a refused viewer reads
 * the posture as helpful. Those are the browser pass's job.
 */
const PAGE = path.join(process.cwd(), "app", "(app)", "settings", "workspace", "people");
const PEOPLE_ACCESS = path.join(process.cwd(), "app", "components", "settings", "PeopleAccess.tsx");
const MEMBERS_PANEL = path.join(process.cwd(), "app", "components", "settings", "MembersPanel.tsx");
const DOMAINS_PANEL = path.join(process.cwd(), "app", "components", "settings", "AllowedDomainsPanel.tsx");
const USERS_BROWSER = path.join(process.cwd(), "app", "components", "records", "users", "UsersBrowser.tsx");
const ARRIVAL_HOOK = path.join(process.cwd(), "app", "hooks", "useSectionArrival.ts");

function source(file: string): string {
    return readFileSync(file, "utf8");
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

describe("people & access owns the sections the manifest promises", () => {
    it("serves the canonical route the manifest gives the workspace people group", () => {
        const group = SETTINGS_GROUPS.find((candidate) => candidate.id === "workspace.people");

        expect(group?.route).toBe(PEOPLE_ROUTE);
        expect(existsSync(path.join(PAGE, "page.tsx"))).toBe(true);
    });

    it("renders an anchor for every section the manifest files under the group", () => {
        const page = source(PEOPLE_ACCESS);
        const missing = MANIFEST_PEOPLE_SECTIONS.filter((section) => !page.includes(`"${section}"`));

        expect(
            MANIFEST_PEOPLE_SECTIONS.length,
            "the manifest must still file the sections this page exists to hold",
        ).toBe(4);
        expect(
            missing,
            "a section the manifest promises a deep link for has no anchor on the page that owns it",
        ).toEqual([]);
    });

    it("holds every manifest section, and the one destination the manifest recorded as a gap", () => {
        expect([...PEOPLE_SECTIONS]).toEqual([
            "members",
            "roles",
            "allowed-domains",
            "directory",
            "member-detail",
        ]);
        for (const section of MANIFEST_PEOPLE_SECTIONS) {
            expect(PEOPLE_SECTIONS as readonly string[]).toContain(section);
        }
        expect(
            MANIFEST_PEOPLE_SECTIONS,
            "allowed domains shipped as a tab with no route, which is why it is this page's own section",
        ).not.toContain("allowed-domains");
    });

    it("gives each section a real element to arrive at", () => {
        const page = source(PEOPLE_ACCESS);
        const anchored = PEOPLE_SECTIONS.filter(
            (section) => page.includes(`section="${section}"`) || page.includes(`id="${section}"`),
        );

        expect(anchored.length).toBe(PEOPLE_SECTIONS.length);
    });

    it("builds every deep link from one place, so an anchor cannot be spelled two ways", () => {
        expect(peopleSectionHref("roles")).toBe("/settings/workspace/people#roles");

        const strays = appFiles(path.join(process.cwd(), "app"))
            .filter((file) => file !== path.join(process.cwd(), "app", "lib", "peopleSections.ts"))
            .filter((file) => source(file).includes(`${PEOPLE_ROUTE}#`));

        expect(
            strays.map((file) => path.relative(process.cwd(), file)),
            "route a People & access deep link through peopleSectionHref rather than writing the fragment out",
        ).toEqual([]);
    });
});

describe("people & access arrives at the section a deep link asked for", () => {
    it("scrolls itself, because a client-side navigation resolves the fragment against the loading fallback and discards it", () => {
        const hook = source(ARRIVAL_HOOK);

        expect(hook).toContain("element.scrollIntoView(");
        expect(hook, "the re-assert catches sections still settling their heights on the first call")
            .toContain("requestAnimationFrame(() => element.scrollIntoView(");
        expect(hook, "reduced motion is honored").toContain('reduceMotion ? "auto" : "smooth"');
    });

    it("keys its guard per navigation, so returning to a section scrolls again", () => {
        const hook = source(ARRIVAL_HOOK);

        expect(hook).toContain("if (scrolledForHash.current !== hash) scrolledForHash.current = null;");
        expect(hook).toContain("scrolledForHash.current === hash");
    });

    it("ignores a fragment that names no section of this page", () => {
        const hook = source(ARRIVAL_HOOK);

        expect(hook).toContain("sections.includes(target) ? target : null");
    });

    it("marks the section it arrived at, and lets the mark expire", () => {
        const page = source(PEOPLE_ACCESS);
        const hook = source(ARRIVAL_HOOK);

        expect(page).toContain("data-arrived");
        expect(hook).toContain("setArrived(null)");
    });
});

describe("people & access gates its sections without hiding them", () => {
    it("gates the roles section on the permission its read requires, in place", () => {
        const page = source(PEOPLE_ACCESS);

        expect(page).toContain('usePermissionCheck("ROLE_MANAGE")');
        expect(page).toContain('usePermissionCheck("WORKSPACE_SETTINGS")');
        expect(page, "a refused section explains itself where it stands rather than vanishing")
            .toContain("SettingsAvailabilityNotice");
        expect(page).toContain('state={check === "denied" ? "ask-admin" : "retry"}');
    });

    it("keeps the page itself ungated, because its roster renders for any member", () => {
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.people");

        expect(entry?.access.permissions).toEqual([]);
        expect(entry?.access.orgAdmin).toBe(false);
        expect(
            entry?.access.manage,
            "every permission this page names gates writes or a section, never the destination",
        ).toEqual(["MEMBER_MANAGE", "ROLE_MANAGE", "WORKSPACE_SETTINGS"]);
    });

    it("leaves the manifest's section entries describing the routes they still serve", () => {
        const roles = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.roles");

        expect(roles?.currentRoute).toBe("/settings/roles");
        expect(roles?.kind).toBe("destination");
        expect(
            roles?.access.permissions,
            "the legacy route still refuses the whole page; only the consolidated one refuses a section of it",
        ).toEqual(["ROLE_MANAGE"]);
    });
});

describe("the shipped panels still render in the home they had", () => {
    it("keeps allowed domains in the members panel's tab strip on the legacy route", () => {
        const panel = source(MEMBERS_PANEL);

        expect(panel).toContain('const showDomains = presentation === "legacy";');
        expect(panel).toContain('presentation = "legacy",');
        expect(panel, "the legacy tab renders the same panel the consolidated section does")
            .toContain("<AllowedDomainsPanel />");
        expect(panel).toContain('{showDomains && (\n                                <TabsContent value="domains">');
    });

    it("names the consolidated invite journey as the founder ruling names it", () => {
        const panel = source(MEMBERS_PANEL);
        const english = JSON.parse(
            readFileSync(path.join(process.cwd(), "messages", "en", "workspace.json"), "utf8"),
        ) as { WorkspaceMembers: Record<string, string> };
        const japanese = JSON.parse(
            readFileSync(path.join(process.cwd(), "messages", "ja", "workspace.json"), "utf8"),
        ) as { WorkspaceMembers: Record<string, string> };

        expect(panel).toContain('t("inviteMemberTitle")');
        expect(english.WorkspaceMembers.inviteMemberTitle).toBe("Invite member");
        expect(japanese.WorkspaceMembers.inviteMemberTitle).toBe("メンバーを招待");
    });

    it("keeps the users browser owning its own page shell on its own route", () => {
        const browser = source(USERS_BROWSER);

        expect(browser).toContain("<PageShell>");
        expect(browser, "the section presentation drops the shell and the page header, not the browser")
            .toContain('const embedded = presentation === "section";');
        expect(browser).toContain("if (embedded) {");
    });

    it("lets the domains panel label itself only where nothing else does", () => {
        const panel = source(DOMAINS_PANEL);

        expect(panel).toContain('const labelled = presentation === "tab";');
        expect(panel).toContain('{labelled && <p className="max-w-prose text-sm text-muted-foreground">');
    });

    it("leaves the legacy pages pointing at the panels they always rendered", () => {
        const members = source(path.join(process.cwd(), "app", "(app)", "settings", "members", "page.tsx"));
        const roles = source(path.join(process.cwd(), "app", "(app)", "settings", "roles", "page.tsx"));
        const users = source(path.join(process.cwd(), "app", "(app)", "users", "page.tsx"));

        expect(members).toContain("<MembersPanel currentUserId={user?.id ?? null} />");
        expect(members, "the legacy route takes the default presentation, so it is unchanged")
            .not.toContain("presentation=");
        expect(roles).toContain("<RolesPanel />");
        expect(users).toContain("<UsersBrowser users={users} />");
        expect(users).not.toContain("presentation=");
    });
});

describe("people & access wears its own chrome", () => {
    it("steps out of the legacy settings header and tab strip", () => {
        const chrome = source(
            path.join(process.cwd(), "app", "components", "settings", "WorkspaceSettingsChrome.tsx"),
        );

        expect(chrome).toContain("OWN_CHROME_ROUTES");
        expect(chrome, "the bail-out is read from the manifest, so a later group inherits it")
            .toContain("SETTINGS_GROUPS.map((group) => group.route)");
        expect(chrome).toContain("if (OWN_CHROME_ROUTES.has(pathname)) return null;");
    });

    it("names itself with the key the navigation labels the group with", () => {
        const page = source(PEOPLE_ACCESS);
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.people");

        expect(page).toContain('tNav("groupPeopleAccess")');
        expect(entry?.titleKey).toBe("SettingsNav.groupPeopleAccess");
    });

    it("draws a loading skeleton for the shape it will become", () => {
        const skeleton = source(path.join(PAGE, "loading.tsx"));

        expect(skeleton).toContain("@/components/ui/skeleton");
        expect(skeleton, "the settings layout owns the shell; a second one makes the page jump")
            .not.toContain("<PageShell>");
        expect(skeleton).not.toContain("useTranslations");
    });
});
