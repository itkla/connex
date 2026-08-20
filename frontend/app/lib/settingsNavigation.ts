import {
    SETTINGS_ENTRIES,
    SETTINGS_GROUPS,
    type SettingsAccess,
    type SettingsCapabilityKey,
    type SettingsEntry,
    type SettingsGroup,
    type SettingsScope,
} from "@/app/lib/settingsManifest";
import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * What the unified Settings navigation knows about the person reading it.
 *
 * Fail-closed by construction: the app shell publishes an empty permission set when its lookup
 * fails, so an unresolved permission hides a destination rather than offering one the backend will
 * refuse. Capabilities are the opposite case and are deliberately so — see
 * {@link capabilitiesSatisfied}.
 */
export type SettingsNavViewer = {
    /** The resolved instance capabilities, or null when their lookup failed. */
    capabilities: InstanceCapabilities | null;
    /** The viewer's effective workspace permissions. */
    permissions: ReadonlySet<string>;
    /** Whether the viewer holds any organization role in the active workspace's organization. */
    isOrgAdmin: boolean;
};

/** Everything the navigation needs to render itself in the reader's language. */
export type SettingsNavContext = {
    viewer: SettingsNavViewer;
    /** Resolves a message key from the manifest to its rendered string in the active locale. */
    translate: (key: string) => string;
    /** The scope word itself: "Personal", "Workspace", "Organization". */
    scopeNames: Readonly<Record<SettingsScope, string>>;
    /** The active workspace's name, or null when it could not be resolved. */
    workspaceName: string | null;
    /** The active organization's name, or null when it could not be resolved. */
    organizationName: string | null;
};

/** One routed settings destination as the navigation offers it. */
export type SettingsNavDestination = {
    /** The manifest entry id. */
    id: string;
    /** The destination's shipped label in the active locale. */
    title: string;
    /** Where the destination lives today. */
    href: string;
    /** The destination's command-palette aliases, for settings search; empty when it has none. */
    aliases: string;
};

/** One scope group as the navigation offers it, with the destinations it currently owns. */
export type SettingsNavGroup = {
    /** The manifest group id. */
    id: string;
    scope: SettingsScope;
    /** The group's label in the active locale. */
    title: string;
    /** The group's landing destination today; see {@link resolveSettingsNavigation}. */
    href: string;
    destinations: readonly SettingsNavDestination[];
    /**
     * The jobs a served canonical destination absorbed, each addressed at its section of it. Empty
     * until the group's canonical route exists; see {@link resolveSettingsNavigation}.
     *
     * These are not navigation rows — the group offers one destination once it has one. They are
     * what settings search matches, so a reader who types the name the job used to have arrives at
     * the section that now holds it instead of finding nothing.
     */
    sections: readonly SettingsNavDestination[];
};

/** One authorization scope as a navigation section. */
export type SettingsNavScope = {
    scope: SettingsScope;
    /** The scope word on its own, for the heading's primary text. */
    name: string;
    /** The workspace or organization this scope governs, or null for the personal scope. */
    qualifier: string | null;
    /** The heading as one string — "Workspace · Northstar" — for search results and labels. */
    label: string;
    /** The DOM id of this scope's section, so the scope spine can jump to it. */
    anchor: string;
    groups: readonly SettingsNavGroup[];
};

/** The scope-grouped settings navigation, in the manifest's order, filtered to what the viewer may see. */
export type SettingsNavModel = readonly SettingsNavScope[];

/** One settings-search hit: a destination, with the scope and group that give it context. */
export type SettingsNavSearchResult = {
    id: string;
    title: string;
    href: string;
    scopeLabel: string;
    groupTitle: string;
};

const SCOPE_ORDER: readonly SettingsScope[] = ["personal", "workspace", "organization"];

const MANIFEST_GROUPS: readonly SettingsGroup[] = SETTINGS_GROUPS;
const MANIFEST_ENTRIES: readonly SettingsEntry[] = SETTINGS_ENTRIES;

/**
 * Reads one boolean instance capability by its manifest key.
 *
 * Exhaustive by design: a capability that is renamed or dropped fails to compile here rather than
 * silently resolving to `undefined` and gating nothing.
 *
 * @param capabilities - the resolved instance capabilities
 * @param key - the dotted capability key the manifest names
 * @returns whether the capability is on
 */
export function capabilityValue(
    capabilities: InstanceCapabilities,
    key: SettingsCapabilityKey,
): boolean {
    switch (key) {
        case "sso":
            return capabilities.sso;
        case "mailManaged":
            return capabilities.mailManaged;
        case "businessCardScanning":
            return capabilities.businessCardScanning;
        case "businessCardImport":
            return capabilities.businessCardImport;
        case "campaignDelivery":
            return capabilities.campaignDelivery;
        case "socialLogin.google":
            return capabilities.socialLogin.google;
        case "socialLogin.microsoft":
            return capabilities.socialLogin.microsoft;
        case "connectedAccounts.google":
            return capabilities.connectedAccounts.google;
        case "connectedAccounts.microsoft":
            return capabilities.connectedAccounts.microsoft;
        case "connectedCapture.google":
            return capabilities.connectedCapture.google;
        case "connectedCapture.microsoft":
            return capabilities.connectedCapture.microsoft;
        default: {
            const unreachable: never = key;
            return unreachable;
        }
    }
}

/**
 * Whether the navigation may offer a destination given what its capabilities resolved to.
 *
 * Three answers, and none of them is "it depends on how the reader got here":
 *
 * - **Unresolved.** A failed capability lookup keeps the destination visible. The reader is told the
 *   state on arrival instead of watching a destination they use every day disappear because one
 *   request failed.
 * - **Resolved against, and the destination declares its {@link SettingsAccess.states}.** Still
 *   visible. #1340 forbids a capability-managed destination from silently vanishing: it stays
 *   discoverable and explains itself in place, which is exactly what a declared state is a promise
 *   to do. Hiding it here would leave the explanation reachable only by typing the URL.
 * - **Resolved against, and it declares nothing.** Hidden, as it is today. A destination that cannot
 *   say why it is empty has nothing to offer a reader who arrives, and advertising it would be the
 *   worse half of the same dishonesty.
 *
 * <p>The "declares states" test reads {@code access.states.length} rather than checking for the
 * capability-explaining states specifically ({@code managed}/{@code not-enabled}); that coarser
 * predicate is safe only because the manifest gate requires every capability-gated entry to declare
 * one of those two. If that gate ever loosens, tighten this check with it.
 */
function capabilitiesSatisfied(access: SettingsAccess, capabilities: InstanceCapabilities | null): boolean {
    if (access.capabilities.length === 0) return true;
    if (capabilities === null) return true;
    const met = access.capabilities.map(
        (requirement) => capabilityValue(capabilities, requirement.key) === requirement.expected,
    );
    const satisfied = access.capabilityMatch === "all" ? met.every(Boolean) : met.some(Boolean);
    return satisfied || access.states.length > 0;
}

/**
 * Whether the navigation may offer a destination to this viewer.
 *
 * Reads the manifest's visibility bucket only — `permissions`, `capabilities`, and `orgAdmin`. The
 * manage bucket (`manage`, `orgWrite`) gates writes on a page that renders for everyone, so hiding
 * on it would hide a page that works today; #1340 codifies that split and this honors it.
 *
 * A refused permission and a refused capability are not the same refusal. A permission the reader
 * does not hold hides the destination: §6's rule is to prefer not rendering the entry point over
 * showing a locked door, and the permission set is fail-closed, so an unreadable one hides too. A
 * capability is a fact about the instance rather than about the reader, and every reader gets the
 * same answer — so a destination that can state that answer keeps its place. See
 * {@link capabilitiesSatisfied}.
 *
 * @param entry - the manifest entry
 * @param viewer - who is reading
 * @returns whether the destination appears in the navigation
 */
export function entryVisible(entry: SettingsEntry, viewer: SettingsNavViewer): boolean {
    if (entry.access.orgAdmin && !viewer.isOrgAdmin) return false;
    if (entry.access.permissions.some((permission) => !viewer.permissions.has(permission))) return false;
    return capabilitiesSatisfied(entry.access, viewer.capabilities);
}

/** Whether a route pattern is a concrete address rather than a parameterized one. */
function navigable(route: string): boolean {
    return !route.includes("[");
}

/**
 * The destinations a group can offer today, in the manifest's order.
 *
 * A group's canonical route does not exist yet, so the navigation offers the entries the manifest
 * files under it. Three of them cannot be offered: a redirect stub is not a destination, an entry
 * with no shipped label has nothing to render, and a parameterized route is not an address.
 */
function groupDestinations(
    groupId: string,
    context: SettingsNavContext,
): readonly SettingsNavDestination[] {
    return MANIFEST_ENTRIES.filter(
        (entry) =>
            entry.group === groupId
            && entry.kind === "destination"
            && entry.titleKey !== null
            && navigable(entry.currentRoute)
            && entryVisible(entry, context.viewer),
    ).map((entry) => ({
        id: entry.id,
        title: context.translate(entry.titleKey ?? ""),
        href: entry.currentRoute,
        aliases: entry.aliasKey === null ? "" : context.translate(entry.aliasKey),
    }));
}

/**
 * The sections of a group whose canonical destination is served: every entry the manifest files
 * under it that names a section of that destination, addressed at its deep link.
 *
 * Visibility is deliberately not re-applied here. A section's own entry may carry a visibility gate
 * describing the *old* route, where the whole page was refused; on the consolidated destination the
 * same gate refuses one section and the page explains it in place. Hiding the section from search
 * would put the reader back where #1340 started: a name they know, leading nowhere.
 */
function groupSections(
    group: SettingsGroup,
    context: SettingsNavContext,
): readonly SettingsNavDestination[] {
    return MANIFEST_ENTRIES.filter(
        (entry) =>
            entry.group === group.id
            && entry.canonicalSection !== null
            && entry.canonicalRoute === group.route
            && entry.titleKey !== null,
    ).map((entry) => ({
        id: entry.id,
        title: context.translate(entry.titleKey ?? ""),
        href: `${entry.canonicalRoute}#${entry.canonicalSection}`,
        aliases: entry.aliasKey === null ? "" : context.translate(entry.aliasKey),
    }));
}

/** The heading for a scope: the scope word, qualified by the thing it governs where there is one. */
function scopeQualifier(scope: SettingsScope, context: SettingsNavContext): string | null {
    if (scope === "workspace") return context.workspaceName;
    if (scope === "organization") return context.organizationName;
    return null;
}

/**
 * Builds the scope-grouped settings navigation from the committed manifest.
 *
 * The navigation renders groups, because a group is the unit of canonical ownership in #1340 and
 * the destination each one will own once the routes move.
 *
 * A group's landing destination follows the migration rather than being re-decided per group. Where
 * the group's canonical route is not served yet, the group links to the first entry the manifest
 * files under it that the viewer can reach; `SETTINGS_ENTRIES` is committed in route order, so that
 * resolution is deterministic, though it is not editorial. **Once the canonical route is served,
 * that destination is the group** — it becomes the landing, and it is the only destination the
 * group offers, because the entries it consolidated are now sections of it rather than peers
 * beside it. Offering both would put the same job under two names in one list, which is the failure
 * #1340 exists to remove. The old addresses keep working; they simply stop being advertised twice.
 *
 * A group with no reachable destination is dropped, and a scope with no remaining group is dropped
 * with it: a scope heading over nothing would advertise administration the reader cannot perform.
 *
 * @param context - the viewer, the active locale's translator, and the scope names
 * @returns the scopes, groups, and destinations to render, in manifest order
 */
export function resolveSettingsNavigation(context: SettingsNavContext): SettingsNavModel {
    const scopes: SettingsNavScope[] = [];
    for (const scope of SCOPE_ORDER) {
        const groups: SettingsNavGroup[] = [];
        const scoped = MANIFEST_GROUPS.filter((group) => group.scope === scope)
            .slice()
            .sort((left, right) => left.order - right.order);
        for (const group of scoped) {
            const reachable = groupDestinations(group.id, context);
            const canonical = reachable.find((destination) => destination.href === group.route);
            const destinations = canonical ? [canonical] : reachable;
            const sections = canonical ? groupSections(group, context) : [];
            const landing = destinations[0];
            if (!landing) continue;
            groups.push({
                id: group.id,
                scope,
                title: group.titleKey === null ? group.epicName : context.translate(group.titleKey),
                href: landing.href,
                destinations,
                sections,
            });
        }
        if (groups.length === 0) continue;
        const qualifier = scopeQualifier(scope, context);
        const name = context.scopeNames[scope];
        scopes.push({
            scope,
            name,
            qualifier,
            label: qualifier === null ? name : `${name} · ${qualifier}`,
            anchor: `settings-scope-${scope}`,
            groups,
        });
    }
    return scopes;
}

/**
 * Finds the destinations matching a settings-search query.
 *
 * Matches a destination's own label, its command-palette aliases, its group's label, and its scope
 * heading, so both the word the reader knows the page by and the word the consolidation gave it
 * lead to the same place. Purely client-side over the manifest — there are two dozen destinations,
 * and a settings search that needs the network is a settings search that stutters.
 *
 * A migrated group's absorbed sections are searched beside its destination, and each carries its
 * own deep link. Consolidation moves where a job lives; it must not take away the word the reader
 * has always found it by.
 *
 * @param model - the resolved navigation
 * @param query - what the reader typed
 * @returns the matching destinations in navigation order; empty for a blank query
 */
export function searchSettingsNavigation(
    model: SettingsNavModel,
    query: string,
): readonly SettingsNavSearchResult[] {
    const needle = query.trim().toLocaleLowerCase();
    if (needle.length === 0) return [];
    const results: SettingsNavSearchResult[] = [];
    for (const scope of model) {
        for (const group of scope.groups) {
            for (const destination of [...group.destinations, ...group.sections]) {
                const haystack = [destination.title, destination.aliases, group.title, scope.label]
                    .join(" ")
                    .toLocaleLowerCase();
                if (!haystack.includes(needle)) continue;
                results.push({
                    id: destination.id,
                    title: destination.title,
                    href: destination.href,
                    scopeLabel: scope.label,
                    groupTitle: group.title,
                });
            }
        }
    }
    return results;
}
