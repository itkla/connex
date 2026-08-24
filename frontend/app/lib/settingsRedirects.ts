import { SETTINGS_ENTRIES, type SettingsEntry } from "@/app/lib/settingsManifest";

/**
 * The search parameters a Next.js page receives, before they are resolved.
 *
 * Mirrors the App Router's own shape rather than narrowing it: a key repeated in the query string
 * arrives as an array, and a redirect that flattened it would quietly drop every value but one.
 */
export type RouteSearchParams = Record<string, string | string[] | undefined>;

/** The manifest's entries at their declared type, for the reads that do not need their literals. */
const MANIFEST_ENTRIES: readonly SettingsEntry[] = SETTINGS_ENTRIES;

/**
 * A manifest entry that forwards rather than renders.
 *
 * Derived from the manifest so a retired id, or an entry that goes back to rendering, is a compile
 * error at the stub that names it rather than a redirect to `undefined` at request time.
 */
type ForwardingEntry = Extract<
    (typeof SETTINGS_ENTRIES)[number],
    { kind: "redirect" | "external-redirect" }
>;

/** The manifest entry ids a redirect stub may name. */
export type SettingsRedirectId = ForwardingEntry["id"];

const FORWARDS: ReadonlyMap<string, string> = new Map(
    MANIFEST_ENTRIES.filter((entry) => entry.redirectsTo !== null).map((entry) => [
        entry.id,
        entry.redirectsTo ?? "",
    ]),
);

/**
 * Serializes resolved search parameters back into a query string.
 *
 * Every parameter survives, including ones no route declares. The manifest's `redirectQuery` names
 * what a forward is known to depend on, but a reader's link may carry anything — a campaign tag, a
 * parameter a later release will read — and a permanent redirect that edits the query is rewriting
 * what someone else shared.
 *
 * @param searchParams - the resolved search parameters, as the App Router hands them over
 * @returns the query string including its leading `?`, or the empty string when there is none
 */
function queryString(searchParams: RouteSearchParams): string {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(searchParams)) {
        if (value === undefined) continue;
        if (Array.isArray(value)) for (const item of value) query.append(key, item);
        else query.append(key, value);
    }
    const serialized = query.toString();
    return serialized.length === 0 ? "" : `?${serialized}`;
}

/**
 * The permanent-redirect target for one retired settings address, query state included.
 *
 * The address itself is the manifest's — a stub names its entry id and nothing else, so a
 * destination that moves again takes its redirects with it and no page has to be found and edited.
 * The query string is the reader's and is carried through whole. The fragment is the manifest's
 * too, and is appended last because that is where a URL puts it: `/route?query#section`.
 *
 * @param id - the manifest entry id of the address being retired
 * @param searchParams - the resolved search parameters of the incoming request
 * @returns the absolute app-relative target to forward to
 * @throws when the id names no forwarding entry, which {@link SettingsRedirectId} already prevents
 */
export function settingsRedirectTarget(
    id: SettingsRedirectId,
    searchParams: RouteSearchParams,
): string {
    const target = FORWARDS.get(id);
    if (target === undefined) {
        throw new Error(`settingsRedirectTarget: ${id} names no forwarding entry in SETTINGS_ENTRIES`);
    }
    const [route, fragment] = target.split("#");
    const query = queryString(searchParams);
    return fragment === undefined ? `${route}${query}` : `${route}${query}#${fragment}`;
}
