import { getCurrentUserFromCookie, getTagsFromCookie, type Tag } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

const cookie = (await headers()).get('cookie');
const user = await getCurrentUserFromCookie(cookie);
if (!user) {
    redirect('/auth/login');
}   

export default async function TagsLibraryPage() {
    const allTags = await getTagsFromCookie(cookie);
    return (
        <div>
            <h1>Tags</h1>
            <h2>All Tags</h2>
            <ul>
                {allTags.map((tag: Tag) => (
                    <li key={tag.id}>{tag.name}</li>
                ))}
            </ul>
        </div>
    )
}