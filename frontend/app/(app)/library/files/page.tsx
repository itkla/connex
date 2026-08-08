import { getCurrentUserResultFromCookie } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import FilesBrowser from "@/app/components/library/files/FilesBrowser";

export default async function FilesLibraryPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }

    return (
        <Suspense>
            <FilesBrowser />
        </Suspense>
    );
}
