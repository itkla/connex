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
function statusParam(raw: string | string[] | undefined): 'open' | 'closed' | undefined {
    const value = Array.isArray(raw) ? raw[0] : raw;
    return value === 'open' || value === 'closed' ? value : undefined;
}

export default async function DealsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const status = statusParam((await searchParams).status);
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [dealsPage, metrics, facets, savedViews]: [Page<Deal>, DealMetrics, DealFacets, SavedView[]] = await Promise.all([
        getDealsPage({ page: 1, size: 25, status }, init),
        getDealMetricsFromCookie(cookie),
        getDealFacetsFromCookie(cookie),
        getSavedViewsFromCookie("deal", cookie),
    ]);

    return <DealsBrowser deals={dealsPage.items} total={dealsPage.total} metrics={metrics} serverFacets={facets} savedViews={savedViews} />;
}
