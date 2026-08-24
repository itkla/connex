import { permanentRedirect } from "next/navigation";

import { settingsRedirectTarget, type RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The retired address for workspace invitations and membership (#1340 WS4.6).
 *
 * Forwards permanently to the account invitations page. Still the legacy address, because the canonical Workspaces & invitations destination has not shipped. It forwards to the page that works today and moves with the manifest when the personal scope lands.
 *
 * The target is read from the manifest rather than spelled here, so a destination that moves takes
 * its redirects with it, and the reader’s query string survives the hop whole.
 */
export default async function MembershipSettingsPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(settingsRedirectTarget("legacy.settings-membership", await searchParams));
}
