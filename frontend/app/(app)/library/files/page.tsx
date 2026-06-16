import { getAllAttachmentsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import type { Attachment } from "@/app/lib/types";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import FilesBrowser from "@/app/components/library/files/FilesBrowser";

export default async function FilesLibraryPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const files: Attachment[] = await getAllAttachmentsFromCookie(cookie);

    return <FilesBrowser attachments={files} />;
}