import { headers } from "next/headers";
import { type CookieResult, getCurrentUserResultFromCookie, getDefaultSavedViewFromCookie, getSavedViewsResultFromCookie } from "@/app/lib/api";
import { type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
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
