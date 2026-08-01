import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import {
    DEFAULT_CAPABILITIES,
    getCapabilities,
    getCaptureOverviewResultFromCookie,
} from "@/app/lib/api";
import type { ConnectedAccountProvider, ProviderCaptureOverview } from "@/app/lib/types";
import { captureConnectionsHref, providerCaptureEnabled } from "@/app/lib/connectedCapture";

export async function generateMetadata() {
    const t = await getTranslations("AccountCaptureReviews");
    return { title: t("metadata.title"), description: t("metadata.description") };
}

function pendingCount(provider: ProviderCaptureOverview): number {
    return provider.reviewCount + provider.pendingApprovalCount;
}

/**
 * Resolves the provider whose review queue should open: the one with the most pending items, falling
 * back to the first capture-enabled provider so the destination is stable even when nothing is
 * waiting.
 */
function resolveProvider(
    providers: readonly ProviderCaptureOverview[],
    enabled: readonly ConnectedAccountProvider[],
): ConnectedAccountProvider | null {
    const enabledSet = new Set(enabled);
    const candidates = providers.filter((provider) => enabledSet.has(provider.provider));
    const busiest = candidates
        .filter((provider) => pendingCount(provider) > 0)
        .sort((a, b) => pendingCount(b) - pendingCount(a))[0];
    return busiest?.provider ?? candidates[0]?.provider ?? enabled[0] ?? null;
}

/**
 * Stable address for the connected-capture review queue. The queue itself is a panel of the
 * account-connections page, whose canonical URL needs a provider; this route resolves that provider
 * server-side so the sidebar and command palette can link to one fixed path.
 */
export default async function CaptureReviewsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilities = await getCapabilities(cookie ? { headers: { cookie } } : {})
        .catch(() => DEFAULT_CAPABILITIES);
    const enabled = (["google", "microsoft"] as const).filter((provider) =>
        providerCaptureEnabled(capabilities, provider),
    );
    if (enabled.length === 0) {
        redirect("/account/connections");
    }

    const overviewResult = await getCaptureOverviewResultFromCookie(cookie);
    const provider = resolveProvider(
        overviewResult.ok ? overviewResult.data.providers : [],
        enabled,
    );
    if (!provider) {
        redirect("/account/connections");
    }

    redirect(captureConnectionsHref(new URLSearchParams(), { provider, panel: "reviews" }));
}
