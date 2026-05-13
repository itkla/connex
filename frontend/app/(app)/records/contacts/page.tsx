import { headers } from "next/headers";
import { getContactsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { redirect } from "next/navigation";

export default async function ContactsPage() {

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    // query api for contact list
    const contacts = await getContactsFromCookie(cookie); 

    return (
        <div>
            <h1>Contacts</h1>
            <ul>
                {contacts.map((contact) => (
                    <li key={contact.id}>{contact.name}</li>
                ))}
            </ul>
        </div>
    );
}
