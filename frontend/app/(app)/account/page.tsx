import { permanentRedirect } from "next/navigation";

import { settingsRedirectTarget, type RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The retired address for the account landing address (#1340 WS4.6).
 *
 * Forwards permanently to the profile page beneath it. Still a personal-scope address, because the canonical Profile destination under the unified Settings shell has not shipped. It forwards to the page that works today and moves with the manifest when the personal scope lands.
 *
 * The target is read from the manifest rather than spelled here, so a destination that moves takes
 * its redirects with it, and the reader’s query string survives the hop whole.
 */
export default async function AccountPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(settingsRedirectTarget("account.home", await searchParams));
}
