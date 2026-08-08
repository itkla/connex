import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie } from "@/app/lib/api";
import TemplateBuilder from "@/app/components/library/documents/TemplateBuilder";

export default async function NewDocumentTemplatePage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect('/auth/login');
    }

    return <TemplateBuilder template={null} />;
}
