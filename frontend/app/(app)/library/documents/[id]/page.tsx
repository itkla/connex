import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import { loadRecord } from "@/app/lib/recordAccess";
import { getCurrentUserFromCookie, getDocumentTemplateById } from "@/app/lib/api";
import type { DocumentTemplate } from "@/app/lib/types";
import TemplateBuilder from "@/app/components/library/documents/TemplateBuilder";

export default async function EditDocumentTemplatePage({ params }: { params: Promise<{ id: string }> }) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init: RequestInit = { headers: cookie ? { cookie } : {}, cache: 'no-store' };
    const templateAccess = await loadRecord<DocumentTemplate>(
        () => getDocumentTemplateById(id, init),
    );

    if (templateAccess.kind === 'forbidden') {
        return <AccessDeniedPage />;
    }
    if (templateAccess.kind === 'missing') {
        notFound();
    }

    return <TemplateBuilder template={templateAccess.record} />;
}
