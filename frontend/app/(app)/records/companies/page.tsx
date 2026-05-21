import { headers } from "next/headers";
import { getCompaniesFromCookie, getContactsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { Company, type Contact } from "@/app/lib/types";
import { redirect } from "next/navigation";
// import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";

export default async function CompaniesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const companies: Company[] = await getCompaniesFromCookie(cookie);

    // return <ContactsBrowser contacts={contacts} />;
    return (
        <CompaniesBrowser companies={companies} />
    )
}
