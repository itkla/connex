import { SETTINGS_ENTRIES } from "@/app/lib/settingsManifest";

/**
 * The addressable sections of the canonical People & access destination (#1340 WS4.3).
 *
 * Four of them are the `canonicalSection` slugs the manifest already files under the
 * `workspace.people` group, so the deep links the manifest promises and the anchors the page
 * renders are one list rather than two that could drift. `allowed-domains` is the fifth: #1340
 * names allowed domains a required per-scope destination, and the manifest recorded it as a route
 * gap because it shipped as a tab inside `MembersPanel` with no address of its own. This page fills
 * that gap, so the section exists here without a legacy route behind it.
 *
 * The order is the page's reading order, not the manifest's: who is here, what a role may do, who
 * may join unasked, and finally the directory for looking someone up.
 */
export const PEOPLE_SECTIONS = [
    "members",
    "roles",
    "allowed-domains",
    "directory",
    "member-detail",
] as const;

/** One addressable section of `/settings/workspace/people`. */
export type PeopleSection = (typeof PEOPLE_SECTIONS)[number];

/** The canonical route the People & access sections live on. */
export const PEOPLE_ROUTE = "/settings/workspace/people";

/**
 * The sections the manifest files under `workspace.people`, in manifest order.
 *
 * Read from the manifest rather than restated, so a section slug that is renamed or retired there
 * fails this module's gate instead of leaving a deep link pointing at nothing.
 */
export const MANIFEST_PEOPLE_SECTIONS: readonly string[] = SETTINGS_ENTRIES.filter(
    (entry) => entry.group === "workspace.people" && entry.canonicalSection !== null,
).map((entry) => entry.canonicalSection as string);

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
