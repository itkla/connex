import { headers } from "next/headers";
import { getCompaniesPage, getCurrentUserFromCookie, getSavedViewsFromCookie } from "@/app/lib/api";
import { Company, type Page, type SavedView } from "@/app/lib/types";
import { redirect } from "next/navigation";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [companiesPage, savedViews]: [Page<Company>, SavedView[]] = await Promise.all([
        getCompaniesPage({ page: 1, size: 25 }, init),
        getSavedViewsFromCookie("company", cookie),
    ]);

    return (
        <CompaniesBrowser companies={companiesPage.items} total={companiesPage.total} savedViews={savedViews} />
    )
}
