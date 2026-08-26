import { permanentRedirect } from "next/navigation";

import { settingsRedirectTarget, type RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The retired address for automation rules (#1340 WS4.6).
 *
 * Forwards permanently to the workflows surface. Rules is absent from the product vocabulary; the surface it named is Workflows, and this address survives only for old bookmarks.
 *
 * The target is read from the manifest rather than spelled here, so a destination that moves takes
 * its redirects with it, and the reader’s query string survives the hop whole.
 */
export default async function RulesSettingsPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(settingsRedirectTarget("legacy.settings-rules", await searchParams));
}
