import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsGroup,
} from "@/app/lib/settingsManifest";

/** The manifest's groups at their declared type, so a group without gap sections is still one. */
const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;

/** The canonical route the personal Connected accounts destination owns. */
export const CONNECTED_ACCOUNTS_ROUTE = "/settings/personal/connected-accounts";

/**
 * The addressable sections of the canonical Connected accounts destination (#1340 WS4.2).
 *
 * One, because this group absorbed one job that had an address of its own beside the connections
 * page itself: the capture review queue, which shipped at `/account/connections/reviews`.
 *
 * **`reviews` is an anchor on the way in rather than on the queue.** The queue is a panel of the
 * connections surface addressed by query — `?provider=google&panel=reviews` — and it exists once
 * per connected provider, so there is no single element on this page that *is* the reviews. The
 * anchor therefore resolves to the provider cards those queues are opened from, which is the same
 * posture `member-detail` takes on People & access: arriving at it lands the reader on the way in,
 * not at the thing. The resolver at `/account/connections/reviews`, which picks the provider with
 * the most pending items, keeps working and keeps forwarding here.
 */
export const CONNECTED_ACCOUNTS_SECTIONS = ["reviews"] as const;

/** One addressable section of `/settings/personal/connected-accounts`. */
export type ConnectedAccountsSection = (typeof CONNECTED_ACCOUNTS_SECTIONS)[number];

/**
 * The sections the manifest files under `personal.connected-accounts` — the ones absorbed from a
 * shipped route, then the route gaps the group declares, of which it has none.
 *
 * Read from the manifest rather than restated, so a section slug that is renamed or retired there
 * fails this module's gate instead of leaving a deep link pointing at nothing.
 */
function manifestConnectedAccountsSections(): readonly string[] {
    const sections: string[] = [];
    for (const entry of SETTINGS_ENTRIES) {
        if (entry.group === "personal.connected-accounts" && entry.canonicalSection !== null) {
            sections.push(entry.canonicalSection);
        }
    }
    const group = MANIFEST_GROUPS.find(
        (candidate) => candidate.id === "personal.connected-accounts",
    );
    for (const section of group?.gapSections ?? []) sections.push(section.slug);
    return sections;
}

export const MANIFEST_CONNECTED_ACCOUNTS_SECTIONS: readonly string[] =
    manifestConnectedAccountsSections();

/**
 * The deep link to one section of the Connected accounts page.
 *
 * Every producer of a Connected accounts section link goes through here, so the page and the things
 * that point into it can never spell an anchor two ways.
 *
 * @param section - the section to arrive at
 * @returns the href, fragment included
 */
export function connectedAccountsSectionHref(section: ConnectedAccountsSection): string {
    return `${CONNECTED_ACCOUNTS_ROUTE}#${section}`;
}
