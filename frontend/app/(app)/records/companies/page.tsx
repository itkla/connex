import { headers } from "next/headers";
import { type CookieResult, getCurrentUserFromCookie, getDefaultSavedViewFromCookie, getSavedViewsResultFromCookie } from "@/app/lib/api";
import { type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const [savedViewsResult, defaultView]: [CookieResult<SavedView[]>, SavedView | null] = await Promise.all([
        getSavedViewsResultFromCookie("company", cookie),
        getDefaultSavedViewFromCookie("company", cookie),
    ]);
    const savedViews = savedViewsResult.ok ? savedViewsResult.data : [];

    return <CompaniesBrowser savedViews={savedViews} defaultView={defaultView} savedViewsUnavailable={!savedViewsResult.ok} />;
}
