import { headers } from "next/headers";

import { getCurrentUserFromCookie } from "@/app/lib/api";
import MembersPanel from "@/app/components/settings/MembersPanel";

export default async function MembersSettingsPage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    return <MembersPanel currentUserId={user?.id ?? null} />;
}
