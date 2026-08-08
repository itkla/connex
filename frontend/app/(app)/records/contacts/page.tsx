import { headers } from "next/headers";
import { type CookieResult, getCurrentUserResultFromCookie, getDefaultSavedViewFromCookie, getSavedViewsResultFromCookie } from "@/app/lib/api";
import { type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";

export default async function ContactsPage() {
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
        getSavedViewsResultFromCookie("person", cookie),
        getDefaultSavedViewFromCookie("person", cookie),
    ]);
    const savedViews = savedViewsResult.ok ? savedViewsResult.data : [];

    // hunter's note: THIS IS A SERVER-SIDE FILTERING PAGE EXPERIMENT. the other pages use frontend server-side filtering/pagination. if this one doesn't work out, switch back to FE SSR
    return <ContactsBrowser savedViews={savedViews} defaultView={defaultView} savedViewsUnavailable={!savedViewsResult.ok} />;
}
