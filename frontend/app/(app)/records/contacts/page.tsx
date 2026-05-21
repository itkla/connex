import { headers } from "next/headers";
import { getContactsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { type Contact } from "@/app/lib/types";
import { redirect } from "next/navigation";
import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";

export default async function ContactsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const contacts: Contact[] = await getContactsFromCookie(cookie);

    return <ContactsBrowser contacts={contacts} />;
}
