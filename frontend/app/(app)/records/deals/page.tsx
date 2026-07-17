import { headers } from "next/headers";
import { getDealsPage, getDealMetricsFromCookie, getDealFacetsFromCookie, getCurrentUserFromCookie, getSavedViewsFromCookie } from "@/app/lib/api";
import { type Deal, type DealFacets, type DealMetrics, type Page, type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import DealsBrowser from "@/app/components/records/deals/DealsBrowser";

/**
 * Maps the URL {@code status} facet value to a server-side deal filter so the initial
 * server render already reflects an active status filter (e.g. a bookmarked "Closed" view),
 * rather than loading the open-heavy first page and filtering it to nothing on the client.
 */
function statusParam(raw: string | string[] | undefined): Array<'open' | 'closed'> | undefined {
    const values = Array.isArray(raw) ? raw : raw ? [raw] : [];
    const normalized = values.filter((value): value is 'open' | 'closed' => value === 'open' || value === 'closed');
    return normalized.length > 0 ? normalized : undefined;
}

/**
 * Picks the currency with the most deals so the initial server render loads that currency's
 * page (matching the client's default currency selection), rather than an all-currency page
 * the client would then re-filter and under-fill.
 */
function dominantCurrency(metrics: DealMetrics): string | undefined {
    let best: string | undefined;
    let bestCount = -1;
    for (const c of metrics.byCurrency) {
        const n = c.openCount + c.closedCount;
        if (n > bestCount) {
            bestCount = n;
            best = c.currency;
        }
    }
    return best;
}

export default async function DealsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const status = statusParam((await searchParams).status);
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [metrics, facets, savedViews]: [DealMetrics, DealFacets, SavedView[]] = await Promise.all([
        getDealMetricsFromCookie(cookie, { status }),
        getDealFacetsFromCookie(cookie),
        getSavedViewsFromCookie("deal", cookie),
    ]);
    const dealsPage: Page<Deal> = await getDealsPage({ page: 1, size: 25, status, currency: dominantCurrency(metrics) }, init);

    return (
        <DealsBrowser
            deals={dealsPage.items}
            total={dealsPage.total}
            metrics={metrics}
            serverFacets={facets}
            savedViews={savedViews}
            timezone={user.timezone}
            currentUserId={user.id}
        />
    );
}
