import { headers } from "next/headers";
import { getCurrentUserFromCookie, getSavedViewsFromCookie } from "@/app/lib/api";
import { type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const savedViews: SavedView[] = await getSavedViewsFromCookie("company", cookie);

    return <CompaniesBrowser savedViews={savedViews} />;
}
