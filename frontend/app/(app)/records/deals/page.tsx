import { headers } from "next/headers";
import { getDealsPage, getCurrentUserFromCookie, getSavedViewsFromCookie } from "@/app/lib/api";
import { type Deal, type Page, type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import DealsBrowser from "@/app/components/records/deals/DealsBrowser";

export default async function DealsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [dealsPage, savedViews]: [Page<Deal>, SavedView[]] = await Promise.all([
        getDealsPage({ page: 1, size: 25 }, init),
        getSavedViewsFromCookie("deal", cookie),
    ]);

    return <DealsBrowser deals={dealsPage.items} total={dealsPage.total} savedViews={savedViews} />;
}
