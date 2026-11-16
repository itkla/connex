import { headers } from "next/headers";
import { type CookieResult, getDealsPage, getDealMetricsFromCookie, getDealFacetsFromCookie, getCurrentUserResultFromCookie, getDefaultSavedViewFromCookie, getMyWorkspacesFromCookie, getSavedViewsResultFromCookie } from "@/app/lib/api";
import { type Deal, type DealFacets, type DealMetrics, type Page, type SavedView } from "@/app/lib/types";
import { resolveWorkspaceTimezone } from "@/app/lib/workspaceSnapshot";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import DealsBrowser from "@/app/components/records/deals/DealsBrowser";
import { currencyCountsForMetrics, resolveDealViewCurrency } from "@/app/components/overview/analytics/currencyScope";

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

export default async function DealsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }
    const workspaceSnapshot = await getMyWorkspacesFromCookie(cookie);
    const timezone = resolveWorkspaceTimezone(workspaceSnapshot, user.timezone);

    const params = await searchParams;
    const status = statusParam(params.status);
    const selectedCurrency = typeof params.currency === 'string' ? params.currency : null;
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [metrics, facets, savedViewsResult, defaultView]: [DealMetrics, DealFacets, CookieResult<SavedView[]>, SavedView | null] = await Promise.all([
        getDealMetricsFromCookie(cookie, { status }),
        getDealFacetsFromCookie(cookie),
        getSavedViewsResultFromCookie("deal", cookie),
        getDefaultSavedViewFromCookie("deal", cookie),
    ]);
    const savedViews = savedViewsResult.ok ? savedViewsResult.data : [];
    const currency = resolveDealViewCurrency(
        currencyCountsForMetrics(metrics), selectedCurrency,
        new Map(facets.currencies.map(({ key, count }) => [key, count])),
    );
    const dealsPage: Page<Deal> = await getDealsPage({ page: 1, size: 25, status, currency }, init);

    return (
        <DealsBrowser
            deals={dealsPage.items}
            total={dealsPage.total}
            metrics={metrics}
            serverFacets={facets}
            savedViews={savedViews}
            defaultView={defaultView}
            savedViewsUnavailable={!savedViewsResult.ok}
            timezone={timezone}
            currentUserId={user.id}
        />
    );
}
