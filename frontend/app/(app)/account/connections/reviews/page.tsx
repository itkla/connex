import { headers } from "next/headers";
import { redirect } from "next/navigation";

import {
    getCapabilitiesResultFromCookie,
    getCaptureOverviewResultFromCookie,
    getProviderConnectionsResultFromCookie,
} from "@/app/lib/api";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import type {
    ConnectedAccountProvider,
    ProviderCaptureOverview,
    ProviderConnection,
} from "@/app/lib/types";
import { CONNECTED_ACCOUNTS_ROUTE } from "@/app/lib/connectedAccountsSections";
import { captureConnectionsHref, providerCaptureEnabled } from "@/app/lib/connectedCapture";

const CONNECTIONS_HREF = CONNECTED_ACCOUNTS_ROUTE;

function pendingCount(provider: ProviderCaptureOverview): number {
    return provider.reviewCount + provider.pendingApprovalCount;
}

function providerWithMostPendingReviewsAmongUserConnections(
    userConnections: readonly ProviderConnection[],
    overviews: readonly ProviderCaptureOverview[],
    instanceEnabledProviders: readonly ConnectedAccountProvider[],
): ConnectedAccountProvider | null {
    const instanceEnabled = new Set(instanceEnabledProviders);
    const usableConnections = userConnections.filter(
        (connection) => instanceEnabled.has(connection.provider) && connection.status !== "revoked",
    );
    if (usableConnections.length === 0) return null;

    const userConnected = new Set(usableConnections.map((connection) => connection.provider));
    const busiestQueue = overviews
        .filter((overview) => userConnected.has(overview.provider) && pendingCount(overview) > 0)
        .sort((a, b) => pendingCount(b) - pendingCount(a))[0];
    if (busiestQueue) return busiestQueue.provider;

    const liveConnection = usableConnections.find((connection) => connection.status === "connected");
    return liveConnection?.provider ?? usableConnections[0].provider;
}

/**
 * The retired address for the connected-capture review queue, which has to resolve a provider before
 * it can forward (#1340 WS4.6).
 *
 * The queue is a panel of the Connected accounts destination and its canonical URL needs a provider,
 * so this address cannot become an ordinary manifest-driven stub: it reads the reader's own
 * connection state, picks the provider with the most pending items, and forwards to that queue. That
 * is why it is one of the two exemptions from the redirect-stub shape, and it never lands a reader
 * in a queue for a provider they have not connected.
 *
 * It forwards to `/settings/personal/connected-accounts` now rather than to `/account/connections`,
 * because the destination moved; the route half is read from the section module so this file spells
 * no address of its own. Nothing in the product links here any more — the sidebar and the palette
 * both resolve `account.capture-reviews` through the manifest, which sends them to that
 * destination's `reviews` section — but the address stays for the bookmarks that already exist.
 *
 * A deployment with no capture capability at all is the one case where this renders instead of
 * forwarding, which is why the breadcrumb registry classifies it as a shell rather than a redirect.
 */
export default async function CaptureReviewsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    const capabilities = capabilitiesResult.data;
    const enabled = (["google", "microsoft"] as const).filter((provider) =>
        providerCaptureEnabled(capabilities, provider),
    );
    if (enabled.length === 0) {
        redirect(CONNECTIONS_HREF);
    }

    const [connectionsResult, overviewResult] = await Promise.all([
        getProviderConnectionsResultFromCookie(cookie),
        getCaptureOverviewResultFromCookie(cookie),
    ]);
    const provider = providerWithMostPendingReviewsAmongUserConnections(
        connectionsResult.ok ? connectionsResult.data : [],
        overviewResult.ok ? overviewResult.data.providers : [],
        enabled,
    );
    if (!provider) {
        redirect(CONNECTIONS_HREF);
    }

    redirect(captureConnectionsHref(new URLSearchParams(), { provider, panel: "reviews" }));
}
