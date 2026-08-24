import { permanentRedirect } from "next/navigation";

import { settingsRedirectTarget, type RouteSearchParams } from "@/app/lib/settingsRedirects";

/**
 * The retired address for the organization’s AI provider (#1340 WS4.6).
 *
 * The job now lives at the `ai-provider` section of AI & data governance, and this path forwards there permanently rather than 404ing an
 * address readers have bookmarked and linked. The target is the manifest’s, not this file’s, so a
 * destination that moves again takes its redirect with it; the query string is the reader’s and
 * survives the hop whole.
 */
export default async function OrganizationAiPage({
    searchParams,
}: {
    searchParams: Promise<RouteSearchParams>;
}) {
    permanentRedirect(settingsRedirectTarget("organization.ai", await searchParams));
}
