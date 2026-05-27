import { getCurrentUserFromCookie, getTagsFromCookie, type Tag } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

const cookie = (await headers()).get('cookie');
const user = await getCurrentUserFromCookie(cookie);
if (!user) {
    redirect('/auth/login');
}

export default async function TagsLibraryPage() {
    const t = await getTranslations("ActivityLibraryTags");
    const allTags = await getTagsFromCookie(cookie);
    return (
        <div>
            <h1>{t("title")}</h1>
            <h2>{t("allTags")}</h2>
            <ul>
                {allTags.map((tag: Tag) => (
                    <li key={tag.id}>{tag.name}</li>
                ))}
            </ul>
        </div>
    )
}
