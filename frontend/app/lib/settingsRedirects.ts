import { SETTINGS_ENTRIES, type SettingsEntry } from "./settingsManifest";

/** One permanent redirect in the shape `next.config.ts`'s `redirects()` returns. */
export type SettingsConfigRedirect = {
    source: string;
    destination: string;
    permanent: true;
};

/** A manifest entry whose address `next.config.ts` forwards, with its fixed target. */
export type ConfigForwardedEntry = SettingsEntry & { redirectsTo: string };

/** The manifest's entries at their declared type, for the reads that do not need their literals. */
const MANIFEST_ENTRIES: readonly SettingsEntry[] = SETTINGS_ENTRIES;

/** The id of a manifest entry that forwards rather than renders. */
type ForwardingId = Extract<
    (typeof SETTINGS_ENTRIES)[number],
    { kind: "redirect" | "external-redirect" }
>["id"];

/**
 * The forwards whose target a page has to look up at request time, so `next.config.ts` cannot
 * forward them and their resolver pages stay.
 *
 * `account.capture-reviews` picks the provider with the most pending reviews among the reader's own
 * connections, and `legacy.settings-workflow` resolves a legacy rule id to the workflow that replaced
 * it. Every other forward has a fixed target the manifest hands over directly.
 */
export const PAGE_RESOLVED_FORWARDS: ReadonlySet<string> = new Set<ForwardingId>([
    "account.capture-reviews",
    "legacy.settings-workflow",
]);

/**
 * Whether a manifest entry is forwarded by `next.config.ts` rather than by a page.
 *
 * @param entry - a settings manifest entry
 * @returns true when the entry forwards to a fixed target that no lookup decides
 */
export function isConfigForwarded(entry: SettingsEntry): entry is ConfigForwardedEntry {
    return entry.kind !== "destination"
        && entry.redirectsTo !== null
        && !PAGE_RESOLVED_FORWARDS.has(entry.id);
}

/**
 * The permanent redirects for every retired settings address, generated from the settings manifest.
 *
 * Next.js applies config redirects before the proxy and before any segment renders. A retired
 * address therefore answers with a real `308`, even one that sits under a streaming `loading.tsx`
 * boundary, where a `permanentRedirect()` thrown by a page ships as a `200` with the redirect in the
 * streamed payload (#1440). The destination is the manifest's, fragment included. Next.js carries
 * the reader's query string through and places it before the fragment.
 *
 * This module imports the manifest relatively so `next.config.ts` loads under either the default
 * SWC loader or `--experimental-next-config-strip-types`. The manifest's own imports are type-only.
 *
 * @returns one permanent redirect per config-forwarded manifest entry, in manifest order
 */
export function settingsConfigRedirects(): readonly SettingsConfigRedirect[] {
    return MANIFEST_ENTRIES.filter(isConfigForwarded).map((entry) => ({
        source: entry.currentRoute,
        destination: entry.redirectsTo,
        permanent: true,
    }));
}
