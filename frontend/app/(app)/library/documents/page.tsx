import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getDocumentTemplates, getCurrentUserResultFromCookie, getUsersFromCookie } from "@/app/lib/api";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import DocumentsLibrary from "@/app/components/library/documents/DocumentsLibrary";

export default async function DocumentsLibraryPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect('/auth/login');
    }

    const [templates, owners] = await Promise.all([
        getDocumentTemplates({
            headers: { cookie: cookie ?? "" },
            cache: "no-store",
        }),
        getUsersFromCookie(cookie),
    ]);

    return <DocumentsLibrary templates={templates} owners={owners} />;
}
