import { headers } from "next/headers";
import { getCompaniesFromCookie, getCurrentUserFromCookie, getSavedViewsFromCookie } from "@/app/lib/api";
import { Company, type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
// import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const [companies, savedViews]: [Company[], SavedView[]] = await Promise.all([
        getCompaniesFromCookie(cookie),
        getSavedViewsFromCookie("company", cookie),
    ]);

    return (
        <CompaniesBrowser companies={companies} savedViews={savedViews} />
    )
}
