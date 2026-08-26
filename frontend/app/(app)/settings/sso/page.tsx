import { permanentRedirect } from "next/navigation";

import { settingsRedirectTarget, type RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The retired address for single sign-on configuration (#1340 WS4.6).
 *
 * Forwards permanently to the single sign-on section of Identity & administrators. Retargeted in #1340 PR 8: it used to forward to the standalone organization SSO page, which now forwards on to the same section. A redirect resolves in one hop, so it names the destination directly.
 *
 * The target is read from the manifest rather than spelled here, so a destination that moves takes
 * its redirects with it, and the reader’s query string survives the hop whole.
 */
export default async function LegacySsoSettingsPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(settingsRedirectTarget("legacy.settings-sso", await searchParams));
}
