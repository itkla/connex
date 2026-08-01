import { headers } from "next/headers";
import { redirect } from "next/navigation";

import {
    DEFAULT_CAPABILITIES,
    getCapabilities,
    getCaptureOverviewResultFromCookie,
    getProviderConnectionsResultFromCookie,
} from "@/app/lib/api";
import type {
    ConnectedAccountProvider,
    ProviderCaptureOverview,
    ProviderConnection,
} from "@/app/lib/types";
import { captureConnectionsHref, providerCaptureEnabled } from "@/app/lib/connectedCapture";

const CONNECTIONS_HREF = "/account/connections";

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
 * Stable address for the connected-capture review queue. The queue itself is a panel of the
 * account-connections page, whose canonical URL needs a provider; this route resolves that provider
 * from the user's own connection state so the sidebar and command palette can link to one fixed
 * path, and never lands a user in a queue for a provider they have not connected.
 */
export default async function CaptureReviewsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilities = await getCapabilities(cookie ? { headers: { cookie } } : {})
        .catch(() => DEFAULT_CAPABILITIES);
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
