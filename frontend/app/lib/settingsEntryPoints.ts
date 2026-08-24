import {
    SETTINGS_ENTRIES,
    type SettingsEntry,
    type SettingsEntryPoint,
} from "@/app/lib/settingsManifest";

/**
 * One settings destination as a navigation surface offers it (#1340 PR 7).
 *
 * The sidebar, the user menu, and the command palette stopped spelling settings routes when this
 * module landed. They name a manifest entry instead and read its address and its name from here, so
 * `SettingsEntry.entryPoints` describes registrations that exist rather than registrations somebody
 * remembered to keep in step — and a destination that moves takes every entry point with it.
 */
export type SettingsDestination = {
    /** The manifest entry id, which is what a navigation surface registers. */
    id: string;
    /** Where the destination is reached today; see {@link settingsDestinationHref}. */
    href: string;
    /** The message key that names this destination on every surface that offers it. */
    titleKey: string;
    /** The message key holding its command-palette aliases, or null when it has none. */
    aliasKey: string | null;
};

/**
 * A manifest entry whose destination can be offered: one that names a label.
 *
 * An entry with no `titleKey` is a bare redirect stub — there is nothing to render on a row — so it
 * is not addressable here and cannot be registered by name. Deriving the id union from the manifest
 * rather than restating it makes a renamed or retired entry a compile error at every entry point
 * that named it.
 */
type LabeledSettingsEntry = Extract<(typeof SETTINGS_ENTRIES)[number], { titleKey: string }>;

/**
 * A labeled entry whose address is concrete rather than a pattern.
 *
 * `workspace.people-detail` is what this excludes today: it serves `/users/[id]`, and a link to one
 * member is an href the caller composes from an id, not an entry point this module can hand out. The
 * exclusion is a type, so the contextual member links a later PR moves cannot resolve to the literal
 * string `/users/[id]` and ship a row that leads nowhere.
 */
type AddressableSettingsEntry = Exclude<
    LabeledSettingsEntry,
    { currentRoute: `${string}[${string}` }
>;

/** The manifest entry ids a navigation surface may register. */
export type SettingsDestinationId = AddressableSettingsEntry["id"];

/** The manifest's entries at their declared type, for the reads that do not need their literals. */
const MANIFEST_ENTRIES: readonly SettingsEntry[] = SETTINGS_ENTRIES;

/**
 * The canonical routes the app actually serves.
 *
 * A group's canonical route only exists once some entry renders it, which is how the settings
 * navigation already decides whether a group has migrated (see `resolveSettingsNavigation`). The
 * entry points use the same fact rather than a second list, so a destination starts being linked at
 * its canonical address in the same commit that starts serving it, and never before.
 */
const SERVED_ROUTES: ReadonlySet<string> = new Set(
    MANIFEST_ENTRIES.filter((entry) => entry.kind === "destination").map((entry) => entry.currentRoute),
);

/**
 * Whether a canonical settings route is served by a page today.
 *
 * Shared with the breadcrumb registry, which derives a canonical destination's trail from the group
 * that owns it and must leave the legacy tables to answer for a group whose route has not shipped.
 *
 * @param route - a canonical route from `SETTINGS_GROUPS`
 * @returns whether some manifest entry renders it
 */
export function settingsRouteServed(route: string): boolean {
    return SERVED_ROUTES.has(route);
}

/**
 * The address a navigation surface links to for one manifest entry.
 *
 * The canonical destination once its route is served, deep-linked at the section that absorbed this
 * job; the route the entry serves today otherwise. Both are the manifest's own fields, so a
 * consolidation that has not shipped keeps its entry points on the working address and a consolidation
 * that has shipped moves them without anyone editing a surface.
 *
 * Null for an entry that serves a route pattern, which is the same fact
 * {@link SettingsDestinationId} excludes at the type level, stated once so the two cannot drift.
 *
 * `workspace.people-detail` is the entry this refuses today. It serves `/users/[id]` — a family of
 * pages, not one destination — and the section it maps to is a stub that lands the reader on the
 * list rather than on a member. Handing either out would give a navigation surface something that
 * looks like an address and is not: the pattern leads nowhere, and the anchor answers a different
 * question than the caller asked. A link to one member is an href its caller composes from an id.
 *
 * @param entry - the manifest entry
 * @returns the href, fragment included where the job became a section of a shared destination, or
 *          null when the entry names no single destination
 */
export function settingsDestinationHref(entry: SettingsEntry): string | null {
    if (entry.currentRoute.includes("[")) return null;
    const href = SERVED_ROUTES.has(entry.canonicalRoute)
        ? (entry.canonicalSection === null
            ? entry.canonicalRoute
            : `${entry.canonicalRoute}#${entry.canonicalSection}`)
        : entry.currentRoute;
    return href.includes("[") ? null : href;
}

function destinationsById(): ReadonlyMap<string, SettingsDestination> {
    const destinations = new Map<string, SettingsDestination>();
    for (const entry of MANIFEST_ENTRIES) {
        if (entry.titleKey === null) continue;
        const href = settingsDestinationHref(entry);
        if (href === null) continue;
        destinations.set(entry.id, {
            id: entry.id,
            href,
            titleKey: entry.titleKey,
            aliasKey: entry.aliasKey,
        });
    }
    return destinations;
}

const DESTINATIONS = destinationsById();

/**
 * The destination a navigation surface registers under one manifest entry id.
 *
 * Total by construction: {@link SettingsDestinationId} is derived from the same array this map is
 * built from, so the guard below cannot fire for a caller that compiles. It refuses rather than
 * returning a half-built row, because a navigation entry with no address is worse than a loud stop.
 *
 * @param id - the manifest entry id
 * @returns the address and message keys the surface renders
 * @throws when the id names no labeled manifest entry, which the id union already prevents
 */
export function settingsDestination(id: SettingsDestinationId): SettingsDestination {
    const destination = DESTINATIONS.get(id);
    if (destination === undefined) {
        throw new Error(`settingsDestination: ${id} names no labeled entry in SETTINGS_ENTRIES`);
    }
    return destination;
}

/**
 * Every destination the manifest says a given surface links to, in manifest order.
 *
 * This is the generated half of the `entryPoints` contract: a surface built from this list and the
 * manifest declaration behind it cannot disagree, and `settingsManifest.test.ts` proves the two
 * agree in both directions for the surfaces that are not built from it directly.
 *
 * @param point - the navigation surface
 * @returns the destinations that surface offers
 */
export function settingsEntryPointDestinations(
    point: SettingsEntryPoint,
): readonly SettingsDestination[] {
    const destinations: SettingsDestination[] = [];
    for (const entry of MANIFEST_ENTRIES) {
        if (!entry.entryPoints.includes(point)) continue;
        const destination = DESTINATIONS.get(entry.id);
        if (destination !== undefined) destinations.push(destination);
    }
    return destinations;
}
