import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

/**
 * The addressable sections of the canonical Communications destination (#1340 WS4.4).
 *
 * Two of them are the `canonicalSection` slugs the manifest files under the `workspace.communications`
 * group; `notification-defaults` is the route gap the same group declares, because #1340 requires a
 * destination for it and none has ever existed. Both come from the manifest, so the deep links it
 * promises and the anchors this page renders are one list rather than two that could drift.
 *
 * The order here is the page's reading order, not the manifest's: how this workspace sends its own
 * mail, how it sends campaigns, and then what it would decide on its members' behalf.
 */
export const COMMUNICATIONS_SECTIONS = ["email", "delivery", "notification-defaults"] as const;

/** One addressable section of `/settings/workspace/communications`. */
export type CommunicationsSection = (typeof COMMUNICATIONS_SECTIONS)[number];

/** The canonical route the Communications sections live on. */
export const COMMUNICATIONS_ROUTE = "/settings/workspace/communications";

/**
 * The sections the manifest files under `workspace.communications` — the ones absorbed from a
 * shipped route, then the route gap the group declares.
 *
 * Read from the manifest rather than restated, so a section slug that is renamed or retired there
 * fails this module's gate instead of leaving a deep link pointing at nothing.
 */
function manifestCommunicationsSections(): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (entry.group === "workspace.communications" && entry.canonicalSection !== null) {
            sections.push(entry.canonicalSection);
        }
    }
    const group = MANIFEST_GROUPS.find((candidate) => candidate.id === "workspace.communications");
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

export const MANIFEST_COMMUNICATIONS_SECTIONS: readonly string[] = manifestCommunicationsSections();

/**
 * The deep link to one section of the Communications page.
 *
 * Every producer of a Communications link goes through here, so the page and the things that point
 * into it can never spell an anchor two ways.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function communicationsSectionHref(section: CommunicationsSection): string {
    return `${COMMUNICATIONS_ROUTE}#${section}`;
}
