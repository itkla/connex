import { getCurrentUserFromCookie, getTagsFromCookie } from "@/app/lib/api";
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

    const tags: Tag[] = await getTagsFromCookie(cookie);

    return <TagsBrowser tags={tags} />;
}
