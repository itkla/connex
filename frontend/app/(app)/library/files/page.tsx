import { getCurrentUserFromCookie } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import FilesBrowser from "@/app/components/library/files/FilesBrowser";

export default async function FilesLibraryPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    return (
        <Suspense>
            <FilesBrowser />
        </Suspense>
    );
}