import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

/**
 * The addressable sections of the canonical CRM configuration destination (#1340 WS4.4).
 *
 * Three of them are the `canonicalSection` slugs the manifest files under the `workspace.crm` group;
 * `workflows` is the route gap the same group declares, because #1340 requires workflow
 * configuration to have a settings home and only the workflows surface itself has ever served it.
 * Both come from the manifest, so the deep links it promises and the anchors this page renders are
 * one list rather than two that could drift.
 *
 * The order here is the page's reading order, not the manifest's: what a record can hold, how a
 * contact is judged against it, what has to be signed off, and what happens without anyone asking.
 */
export const CRM_SECTIONS = [
    "custom-fields",
    "qualification",
    "approval-policies",
    "workflows",
] as const;

/** One addressable section of `/settings/workspace/crm`. */
export type CrmSection = (typeof CRM_SECTIONS)[number];

/** The canonical route the CRM configuration sections live on. */
export const CRM_ROUTE = "/settings/workspace/crm";

/**
 * The sections the manifest files under `workspace.crm` — the ones absorbed from a shipped route,
 * then the route gap the group declares.
 *
 * Read from the manifest rather than restated, so a section slug that is renamed or retired there
 * fails this module's gate instead of leaving a deep link pointing at nothing.
 */
function manifestCrmSections(): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (entry.group === "workspace.crm" && entry.canonicalSection !== null) {
            sections.push(entry.canonicalSection);
        }
    }
    const group = MANIFEST_GROUPS.find((candidate) => candidate.id === "workspace.crm");
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

export const MANIFEST_CRM_SECTIONS: readonly string[] = manifestCrmSections();

/**
 * The deep link to one section of the CRM configuration page.
 *
 * Every producer of a CRM configuration link goes through here, so the page and the things that
 * point into it can never spell an anchor two ways.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function crmSectionHref(section: CrmSection): string {
    return `${CRM_ROUTE}#${section}`;
}
