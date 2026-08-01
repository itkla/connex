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

/**
 * Ranks the providers the user has actually connected, preferring the busiest review queue and
 * falling back to any live connection. Instance capability alone is not a usable signal — it is
 * identical for every user — so a provider the user has not connected never wins, and a user with
 * no usable connection resolves to nothing rather than an empty queue.
 *
 * @param connections - the user's provider connections
 * @param overviews - the per-provider capture overviews
 * @param enabled - the providers whose capture surface the instance enables
 * @returns the provider whose queue should open, or null when there is no usable connection
 */
function resolveProvider(
    connections: readonly ProviderConnection[],
    overviews: readonly ProviderCaptureOverview[],
    enabled: readonly ConnectedAccountProvider[],
): ConnectedAccountProvider | null {
    const enabledSet = new Set(enabled);
    const usable = connections.filter(
        (connection) => enabledSet.has(connection.provider) && connection.status !== "revoked",
    );
    if (usable.length === 0) return null;

    const usableSet = new Set(usable.map((connection) => connection.provider));
    const busiest = overviews
        .filter((overview) => usableSet.has(overview.provider) && pendingCount(overview) > 0)
        .sort((a, b) => pendingCount(b) - pendingCount(a))[0];
    if (busiest) return busiest.provider;

    const live = usable.find((connection) => connection.status === "connected");
    return live?.provider ?? usable[0].provider;
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
    const provider = resolveProvider(
        connectionsResult.ok ? connectionsResult.data : [],
        overviewResult.ok ? overviewResult.data.providers : [],
        enabled,
    );
    if (!provider) {
        redirect(CONNECTIONS_HREF);
    }

    redirect(captureConnectionsHref(new URLSearchParams(), { provider, panel: "reviews" }));
}
