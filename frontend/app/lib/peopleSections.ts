import { SETTINGS_ENTRIES, SETTINGS_GROUPS } from "@/app/lib/settingsManifest";

/**
 * The addressable sections of the canonical People & access destination (#1340 WS4.3).
 *
 * Four of them are the `canonicalSection` slugs the manifest files under the `workspace.people`
 * group; `allowed-domains` is the route gap the same group declares, because it shipped as a tab
 * inside `MembersPanel` with no address of its own. Both come from the manifest, so the deep links
 * it promises and the anchors this page renders are one list rather than two that could drift.
 *
 * The order here is the page's reading order, not the manifest's: who is here, what a role may do,
 * who may join unasked, and finally the directory for looking someone up.
 */
export const PEOPLE_SECTIONS = [
    "members",
    "roles",
    "allowed-domains",
    "directory",
    "member-detail",
] as const;

/**
 * One addressable section of `/settings/workspace/people`.
 *
 * **`member-detail` is a placeholder for a destination this PR does not move.** Its canonical
 * content is the member's own page at `/users/[id]`, which still lives there; the manifest maps it
 * to this section, so the anchor resolves to the rows that open those pages rather than to the
 * profile itself. Arriving at it lands the reader on the way in, not at the thing. The redirect
 * that completes the URL story is #1340's migration PR, and that is when this stops being a stub.
 */
export type PeopleSection = (typeof PEOPLE_SECTIONS)[number];

/** The canonical route the People & access sections live on. */
export const PEOPLE_ROUTE = "/settings/workspace/people";

/**
 * The sections the manifest files under `workspace.people` — the ones absorbed from a shipped
 * route, then the route gap the group declares.
 *
 * Read from the manifest rather than restated, so a section slug that is renamed or retired there
 * fails this module's gate instead of leaving a deep link pointing at nothing.
 */
function manifestPeopleSections(): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (entry.group === "workspace.people" && entry.canonicalSection !== null) {
            sections.push(entry.canonicalSection);
        }
    }
    const group = SETTINGS_GROUPS.find((candidate) => candidate.id === "workspace.people");
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

export const MANIFEST_PEOPLE_SECTIONS: readonly string[] = manifestPeopleSections();

/**
 * The deep link to one section of the People & access page.
 *
 * Every producer of a People & access link goes through here, so the page and the things that point
 * into it can never spell an anchor two ways.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function peopleSectionHref(section: PeopleSection): string {
    return `${PEOPLE_ROUTE}#${section}`;
}
