import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getDocumentTemplates, getCurrentUserResultFromCookie } from "@/app/lib/api";
import { type DocumentTemplate } from "@/app/lib/types";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import DocumentTemplatesBrowser from "@/app/components/library/documents/DocumentTemplatesBrowser";

export default async function DocumentTemplatesPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect('/auth/login');
    }

    const templates: DocumentTemplate[] = await getDocumentTemplates({
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <DocumentTemplatesBrowser templates={templates} />;
}
