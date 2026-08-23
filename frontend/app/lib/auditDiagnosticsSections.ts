import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

/**
 * The addressable sections of the canonical workspace Audit & diagnostics destination (#1340 WS4.4).
 *
 * Both are the `canonicalSection` slugs the manifest files under the `workspace.audit-diagnostics`
 * group; the group declares no route gap, because every job it names already had an address. They
 * are read from the manifest rather than restated, so a section slug that is renamed or retired
 * there fails this module's gate instead of leaving a deep link pointing at nothing.
 *
 * The order here is the page's reading order and the group's own name: what happened, then whether
 * the machinery behind it is healthy.
 */
export const AUDIT_DIAGNOSTICS_SECTIONS = ["audit", "diagnostics"] as const;

/** One addressable section of `/settings/workspace/audit-diagnostics`. */
export type AuditDiagnosticsSection = (typeof AUDIT_DIAGNOSTICS_SECTIONS)[number];

/** The canonical route the workspace Audit & diagnostics sections live on. */
export const AUDIT_DIAGNOSTICS_ROUTE = "/settings/workspace/audit-diagnostics";

/** The sections the manifest files under `workspace.audit-diagnostics`, absorbed and created alike. */
function manifestAuditDiagnosticsSections(): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (entry.group === "workspace.audit-diagnostics" && entry.canonicalSection !== null) {
            sections.push(entry.canonicalSection);
        }
    }
    const group = MANIFEST_GROUPS.find(
        (candidate) => candidate.id === "workspace.audit-diagnostics",
    );
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

export const MANIFEST_AUDIT_DIAGNOSTICS_SECTIONS: readonly string[] =
    manifestAuditDiagnosticsSections();

/**
 * The deep link to one section of the workspace Audit & diagnostics page.
 *
 * Every producer of one goes through here, so the page and the things that point into it can never
 * spell an anchor two ways.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function auditDiagnosticsSectionHref(section: AuditDiagnosticsSection): string {
    return `${AUDIT_DIAGNOSTICS_ROUTE}#${section}`;
}
