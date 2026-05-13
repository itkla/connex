import { headers } from "next/headers";
import { getCompaniesFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { redirect } from "next/navigation";

export default async function CompaniesPage() {

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    // query api for company list
    const companies = await getCompaniesFromCookie(cookie); 

    return (
        <div>
            <h1>Companies</h1>
            <ul>
                {companies.map((company) => (
                    <li key={company.id}>{company.name}</li>
                ))}
            </ul>
        </div>
    );
}
