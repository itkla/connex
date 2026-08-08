import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie, getDealDocumentById } from "@/app/lib/api";
import type { DealDocument } from "@/app/lib/types";
import DocumentPaper from "@/app/components/records/deals/DocumentPaper";

type Params = { id: string; docId: string };

export default async function DealDocumentPrintPage({ params }: { params: Promise<Params> }) {
    const { id, docId } = await params;
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }

    const init: RequestInit = { headers: cookie ? { cookie } : {}, cache: 'no-store' };
    const document: DealDocument | null = await getDealDocumentById(Number(id), Number(docId), init)
        .catch(() => null);

    return <DocumentPaper document={document} />;
}
