import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    AUDIT_DIAGNOSTICS_ROUTE,
    AUDIT_DIAGNOSTICS_SECTIONS,
    MANIFEST_AUDIT_DIAGNOSTICS_SECTIONS,
    auditDiagnosticsSectionHref,
} from "@/app/lib/auditDiagnosticsSections";
import {
    COMMUNICATIONS_ROUTE,
    COMMUNICATIONS_SECTIONS,
    MANIFEST_COMMUNICATIONS_SECTIONS,
    communicationsSectionHref,
} from "@/app/lib/communicationsSections";
import { CRM_ROUTE, CRM_SECTIONS, MANIFEST_CRM_SECTIONS, crmSectionHref } from "@/app/lib/crmSections";
import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/**
 * Gate over the three consolidated scope destinations of #1340 WS4.4 — Communications, CRM
 * configuration, and workspace Audit & diagnostics.
 *
 * The epic's acceptance for this workstream is that seven scattered surfaces resolve to three pages
 * whose sections each keep a stable deep link, that the two jobs the epic requires but nothing
 * implements are named honestly rather than faked, and that no permission boundary loosened on the
 * way. This suite holds the parts of that a browser pass cannot: that each page's anchors are
 * exactly the sections its manifest group promises, that every one is spelled once and reached
 * through a shared href builder, that a failed read is never dressed as an empty result, and that
 * each panel still renders correctly in the home it had before these pages existed.
 *
 * What it deliberately does not assert: how the sections look, whether a refused viewer reads the
 * posture as helpful, and whether an arrival lands where the reader expected. Those are the browser
 * pass's job.
 */
const APP = path.join(process.cwd(), "app");
const COMPONENTS = path.join(APP, "components", "settings");
const COMMUNICATIONS_VIEW = path.join(COMPONENTS, "WorkspaceCommunications.tsx");
const CRM_VIEW = path.join(COMPONENTS, "CrmConfiguration.tsx");
const AUDIT_VIEW = path.join(COMPONENTS, "AuditDiagnostics.tsx");
const SECTION_REGION = path.join(COMPONENTS, "SettingsSectionRegion.tsx");

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
    titleKey: string;
};

const GROUPS: readonly ScopeGroup[] = [
    {
        id: "workspace.communications",
        route: COMMUNICATIONS_ROUTE,
        sections: COMMUNICATIONS_SECTIONS,
        manifestSections: MANIFEST_COMMUNICATIONS_SECTIONS,
        href: communicationsSectionHref as (section: never) => string,
        view: COMMUNICATIONS_VIEW,
        titleKey: "SettingsNav.groupCommunications",
    },
    {
        id: "workspace.crm",
        route: CRM_ROUTE,
        sections: CRM_SECTIONS,
        manifestSections: MANIFEST_CRM_SECTIONS,
        href: crmSectionHref as (section: never) => string,
        view: CRM_VIEW,
        titleKey: "SettingsNav.groupCrmConfiguration",
    },
    {
        id: "workspace.audit-diagnostics",
        route: AUDIT_DIAGNOSTICS_ROUTE,
        sections: AUDIT_DIAGNOSTICS_SECTIONS,
        manifestSections: MANIFEST_AUDIT_DIAGNOSTICS_SECTIONS,
        href: auditDiagnosticsSectionHref as (section: never) => string,
        view: AUDIT_VIEW,
        titleKey: "SettingsNav.groupAuditDiagnostics",
    },
];

describe("each scope destination owns the sections its manifest group promises", () => {
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
        const view = source(group.view);
        const anchored = group.sections.filter(
            (section) => view.includes(`section="${section}"`) || view.includes(`id="${section}"`),
        );

        expect(anchored).toEqual([...group.sections]);
    });

    it.each(GROUPS)("$id registers every section with the arrival hook", (group) => {
        const view = source(group.view);

        expect(view, "a page that does not register cannot be arrived at by fragment").toContain(
            "useSectionArrival(",
        );
        expect(view).toContain("register={register}");
        expect(view).toContain("arrived={arrived}");
    });

    it("builds every deep link from one place, so an anchor cannot be spelled two ways", () => {
        expect(communicationsSectionHref("email")).toBe(`${COMMUNICATIONS_ROUTE}#email`);
        expect(crmSectionHref("workflows")).toBe(`${CRM_ROUTE}#workflows`);
        expect(auditDiagnosticsSectionHref("audit")).toBe(`${AUDIT_DIAGNOSTICS_ROUTE}#audit`);

        const builders = new Set(
            ["communicationsSections", "crmSections", "auditDiagnosticsSections"].map((module) =>
                path.join(APP, "lib", `${module}.ts`),
            ),
        );
        const strays = appFiles(APP)
            .filter((file) => !builders.has(file))
            .filter((file) => {
                const text = source(file);
                return GROUPS.some((group) => text.includes(`${group.route}#`));
            });

        expect(
            strays.map((file) => path.relative(process.cwd(), file)),
            "route a scope-group deep link through its section href builder rather than writing the fragment out",
        ).toEqual([]);
    });
});

describe("the scope destinations gate their sections without hiding them", () => {
    it("communications is offered only to a reader every one of its sections would serve", () => {
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.communications");

        expect(
            entry?.access.permissions,
            "every read behind this page needs the same permission, so a member without it would find only refusals",
        ).toEqual(["WORKSPACE_SETTINGS"]);
        expect(entry?.access.permissionMatch).toBe("all");
        expect(entry?.access.states).toContain("ask-admin");
        expect(entry?.access.states).toContain("retry");
    });

    it("crm configuration stays ungated, because the browser it absorbed already is", () => {
        const entry = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.crm");
        const policies = SETTINGS_ENTRIES.find(
            (candidate) => candidate.id === "workspace.approval-policies",
        );

        expect(entry?.access.permissions, "hiding it would hide a page that works today").toEqual([]);
        expect(policies?.access.permissions, "the shipped browser gates writes only").toEqual([]);
        expect(entry?.access.manage).toContain("CUSTOM_FIELD_MANAGE");
        expect(entry?.access.manage).toContain("WORKSPACE_SETTINGS");
        expect(entry?.access.states, "an ungated destination has no state to explain").toEqual([]);
    });

    it("audit & diagnostics is reachable by either of the two permissions it merged", () => {
        const entry = SETTINGS_ENTRIES.find(
            (candidate) => candidate.id === "workspace.audit-diagnostics",
        );
        const audit = SETTINGS_ENTRIES.find((candidate) => candidate.id === "workspace.audit-log");
        const diagnostics = SETTINGS_ENTRIES.find(
            (candidate) => candidate.id === "workspace.diagnostics",
        );

        expect(audit?.access.permissions).toEqual(["AUDIT_READ"]);
        expect(diagnostics?.access.permissions).toEqual(["WORKSPACE_SETTINGS"]);
        expect(
            entry?.access.permissions,
            "the destination carries both of the permissions its two sections need",
        ).toEqual(["AUDIT_READ", "WORKSPACE_SETTINGS"]);
        expect(
            entry?.access.permissionMatch,
            "requiring both would take the audit log away from a role granted exactly AUDIT_READ",
        ).toBe("any");
    });

    it.each([
        { view: COMMUNICATIONS_VIEW, checks: ['usePermissionCheck("WORKSPACE_SETTINGS")'] },
        {
            view: CRM_VIEW,
            checks: [
                'usePermissionCheck("CUSTOM_FIELD_MANAGE")',
                'usePermissionCheck("WORKSPACE_SETTINGS")',
            ],
        },
        { view: AUDIT_VIEW, checks: ['usePermissionCheck("WORKSPACE_SETTINGS")'] },
    ])("gates each section in place on the permission its own read requires", ({ view, checks }) => {
        const text = source(view);
        for (const check of checks) expect(text).toContain(check);
        expect(text, "a refused section explains itself where it stands rather than vanishing")
            .toContain("SectionRefusal");
        expect(
            text,
            "the unresolved case names the lookup that failed: the viewer's permissions, not a feature's availability",
        ).toContain('retryTitle={t("accessCheckFailedTitle")}');
    });

    it("keeps the refusal and the failed lookup apart in the shared posture", () => {
        expect(source(SECTION_REGION)).toContain(
            'if (check === "denied") return <SettingsAvailabilityNotice variant="inline" state="ask-admin" />;',
        );
    });
});

describe("the scope destinations' copy resolves to finished sentences", () => {
    const NAMESPACES = ["SettingsCommunications", "SettingsCrm", "SettingsAuditDiagnostics", "SettingsGap"];
    /** The only keys these pages render with arguments; everything else must stand on its own. */
    const INTERPOLATED = new Set(["description"]);

    it.each(["en", "ja"])("leaves no unfilled placeholder in the %s catalog", (locale) => {
        const catalog = JSON.parse(
            readFileSync(path.join(process.cwd(), "messages", locale, "settings.json"), "utf8"),
        ) as Record<string, Record<string, string>>;

        const unfilled: string[] = [];
        for (const namespace of NAMESPACES) {
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

    it("passes the workspace name to every string that asks for one", () => {
        for (const view of [COMMUNICATIONS_VIEW, CRM_VIEW, AUDIT_VIEW]) {
            expect(source(view)).toContain(
                't("description", { workspace: activeWorkspace?.name ?? "" })',
            );
        }
    });
});

describe("the consolidations name the jobs they cannot yet do", () => {
    it.each([
        {
            group: "workspace.communications",
            slug: "notification-defaults",
            titleKey: "SettingsCommunications.notificationDefaultsTitle",
        },
        { group: "workspace.crm", slug: "workflows", titleKey: "CommonSidebar.navWorkflows" },
    ])("declares $slug as a route gap, because no route ever served it", ({ group, slug, titleKey }) => {
        const groups: readonly SettingsGroup[] = SETTINGS_GROUPS;
        const manifestGroup = groups.find((candidate) => candidate.id === group);
        const gap = manifestGroup?.gapSections?.find((section) => section.slug === slug);

        expect(gap, "the epic requires this job to be addressable here").toBeDefined();
        expect(gap?.titleKey).toBe(titleKey);
        expect(
            SETTINGS_ENTRIES.some((entry) => entry.canonicalSection === slug),
            "a gap section is exactly the case no entry can describe; an entry here would mean a route exists",
        ).toBe(false);
    });

    it.each([
        { view: COMMUNICATIONS_VIEW, target: '<Link href="/account/notifications">' },
        { view: CRM_VIEW, target: '<Link href="/workflows">' },
    ])("offers the surface that serves the nearest shipped job", ({ view, target }) => {
        const text = source(view);

        expect(text).toContain("<SectionNotYetAvailable");
        expect(text, "the only honest action is the surface that exists").toContain(target);
    });

    it("never dresses a missing feature as a capability state", () => {
        const region = source(SECTION_REGION);
        const gapPosture = region.slice(region.indexOf("function SectionNotYetAvailable"));

        expect(
            gapPosture,
            'a gap is not "not enabled for this deployment" — no operator can turn it on — and not "managed", which would claim someone else already runs it',
        ).not.toContain("SettingsAvailabilityNotice");
        for (const view of [COMMUNICATIONS_VIEW, CRM_VIEW]) {
            const text = source(view);
            expect(text).not.toContain('state="not-enabled"');
            expect(text).not.toContain('state="managed"' + "\n" + '                                body');
        }
    });
});

describe("the scope destinations tell a failed read apart from an empty result", () => {
    it("hands CRM configuration null approval policies when their read failed, never an empty list", () => {
        const page = source(path.join(routeDir(CRM_ROUTE), "page.tsx"));

        expect(page).toContain("let policies: ApprovalPolicy[] | null;");
        expect(page, "an empty list would claim no document in this workspace needs approval")
            .toContain("policies = null;");
        expect(source(CRM_VIEW)).toContain("policies === null");
        expect(source(CRM_VIEW)).toContain('title={t("approvalPoliciesFailedTitle")}');
    });

    it("keeps a refused audit read apart from a failed one, and both apart from an empty log", () => {
        const page = source(path.join(routeDir(AUDIT_DIAGNOSTICS_ROUTE), "page.tsx"));
        const view = source(AUDIT_VIEW);

        expect(page).toContain('{ kind: "refused" }');
        expect(page).toContain('{ kind: "unavailable" }');
        expect(page).toContain('{ kind: "loaded", entries: access.items }');
        expect(
            page,
            "loadCollection sends an unauthenticated caller to sign in by throwing; swallowing that would strand them",
        ).toContain("unstable_rethrow(error);");
        expect(view, "a refusal names who can lift it").toContain('state="ask-admin"');
        expect(view, "a failure offers a retry").toContain('title={t("auditFailedTitle")}');
    });

    it("keeps a failed capability read out of the managed and unmanaged answers alike", () => {
        const page = source(path.join(routeDir(COMMUNICATIONS_ROUTE), "page.tsx"));
        const view = source(COMMUNICATIONS_VIEW);

        expect(page).toContain("capabilities.ok ? capabilities.data.mailManaged : null");
        expect(
            view,
            "answering false would offer an administrator a form whose saves the instance may ignore",
        ).toContain("mailManaged === null");
        expect(view).toContain('state="retry"');
        expect(view).toContain('state="managed"');
    });
});

describe("the shipped panels still render in the home they had", () => {
    it.each([
        { route: "/settings/email", contains: "<EmailPanel />" },
        { route: "/settings/delivery", contains: "<DeliveryPanel />" },
        { route: "/settings/custom-fields", contains: "<CustomFieldsPanel />" },
        { route: "/settings/qualification", contains: "<QualificationCriteriaPanel />" },
        { route: "/settings/diagnostics", contains: '<DiagnosticsPanel scope="workspace" />' },
    ])("leaves $route rendering the panel it always rendered", ({ route, contains }) => {
        expect(source(path.join(routeDir(route), "page.tsx"))).toContain(contains);
    });

    it.each([
        { file: path.join(COMPONENTS, "EmailPanel.tsx"), marker: 'presentation = "page"' },
        { file: path.join(COMPONENTS, "DeliveryPanel.tsx"), marker: 'presentation = "page"' },
        { file: path.join(COMPONENTS, "CustomFieldsPanel.tsx"), marker: 'presentation = "page"' },
        {
            file: path.join(APP, "components", "admin", "AuditLogBrowser.tsx"),
            marker: 'presentation = "page"',
        },
        {
            file: path.join(
                APP,
                "components",
                "records",
                "approval-policies",
                "ApprovalPoliciesBrowser.tsx",
            ),
            marker: "presentation = 'page'",
        },
    ])("defaults every seam to the shipped route's presentation", ({ file, marker }) => {
        expect(source(file)).toContain(marker);
    });

    it("keeps the two browsers owning their own page shell on their own routes", () => {
        const audit = source(path.join(APP, "components", "admin", "AuditLogBrowser.tsx"));
        const policies = source(
            path.join(APP, "components", "records", "approval-policies", "ApprovalPoliciesBrowser.tsx"),
        );

        expect(audit).toContain('if (presentation === "section") return <div');
        expect(audit).toContain("return <PageShell>{children}</PageShell>;");
        expect(policies).toContain("if (presentation === 'section') return <div");
        expect(policies).toContain("return <PageShell>{children}</PageShell>;");
    });

    it("steps a nested panel's headings down instead of repeating the section's level", () => {
        const delivery = source(path.join(COMPONENTS, "DeliveryPanel.tsx"));
        const fields = source(path.join(COMPONENTS, "CustomFieldsPanel.tsx"));

        for (const panel of [delivery, fields]) {
            expect(panel).toContain('const headingLevel = presentation === "section" ? 3 : 2;');
        }
        expect(
            delivery.match(/headingLevel=\{headingLevel\}/g),
            "the two nested channels each receive the level and each apply it",
        ).toHaveLength(4);
        expect(fields.match(/headingLevel=\{headingLevel\}/g)).toHaveLength(1);
    });
});

describe("the scope destinations wear their own chrome", () => {
    it.each(GROUPS)("$id steps out of the legacy settings header and tab strip", (group) => {
        const chrome = source(path.join(COMPONENTS, "WorkspaceSettingsChrome.tsx"));

        expect(
            chrome,
            "the bail-out is read from the manifest, so a migrated group inherits it by existing",
        ).toContain("SETTINGS_GROUPS.map((group) => group.route)");
        expect(SETTINGS_GROUPS.some((candidate) => candidate.route === group.route)).toBe(true);
    });

    it.each(GROUPS)("$id names itself with the key the navigation labels its group with", (group) => {
        expect(source(group.view)).toContain(`tNav("${group.titleKey.split(".")[1]}")`);
    });

    it.each(GROUPS)("$id draws a loading skeleton for the shape it will become", (group) => {
        const skeleton = source(path.join(routeDir(group.route), "loading.tsx"));

        expect(skeleton).toContain("@/components/ui/skeleton");
        expect(skeleton, "the settings layout owns the shell; a second one makes the page jump")
            .not.toContain("<PageShell>");
        expect(skeleton, "a skeleton has no copy").not.toContain("useTranslations");
        expect(skeleton, "bones, not a spinner").not.toContain("animate-spin");
    });

    it("reuses the diagnostics skeleton rather than forking a second one", () => {
        const skeleton = source(path.join(routeDir(AUDIT_DIAGNOSTICS_ROUTE), "loading.tsx"));

        expect(
            skeleton,
            "the panel's own skeleton is shared with its standalone route; the section keeps one shape in both homes",
        ).toContain('<DiagnosticsPanelSkeleton scope="workspace" />');
    });
});
