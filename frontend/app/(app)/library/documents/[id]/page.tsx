import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { getCurrentUserFromCookie, getDocumentTemplateById } from "@/app/lib/api";
import type { DocumentTemplate } from "@/app/lib/types";
import TemplateBuilder from "@/app/components/library/documents/TemplateBuilder";

export default async function EditDocumentTemplatePage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = await params;
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init: RequestInit = { headers: cookie ? { cookie } : {}, cache: 'no-store' };
    const template: DocumentTemplate | null = await getDocumentTemplateById(Number(id), init).catch(() => null);

    if (!template) {
        notFound();
    }

    return <TemplateBuilder template={template} />;
}
