import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getDocumentTemplatesFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { type DocumentTemplate } from "@/app/lib/types";
import DocumentTemplatesBrowser from "@/app/components/library/documents/DocumentTemplatesBrowser";

export default async function DocumentTemplatesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const templates: DocumentTemplate[] = await getDocumentTemplatesFromCookie(cookie);

    return <DocumentTemplatesBrowser templates={templates} />;
}
