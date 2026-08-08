import { getCurrentUserFromCookie, getTags } from "@/app/lib/api";
import type { Tag } from "@/app/lib/types";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import TagsBrowser from "@/app/components/library/tags/TagsBrowser";

export default async function TagsLibraryPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const tags: Tag[] = await getTags({
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <TagsBrowser tags={tags} />;
}
