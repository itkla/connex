import { headers } from "next/headers";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import { redirect } from "next/navigation";
import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";

export default async function ContactsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    // hunter's note: THIS IS A SERVER-SIDE FILTERING PAGE EXPERIMENT. the other pages use frontend server-side filtering/pagination. if this one doesn't work out, switch back to FE SSR
    return <ContactsBrowser />;
}
