import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import TemplateBuilder from "@/app/components/library/documents/TemplateBuilder";

export default async function NewDocumentTemplatePage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    return <TemplateBuilder template={null} />;
}
