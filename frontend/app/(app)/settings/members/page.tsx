import { headers } from "next/headers";

import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie } from "@/app/lib/api";
import MembersPanel from "@/app/components/settings/MembersPanel";

export default async function MembersSettingsPage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    return <MembersPanel currentUserId={user?.id ?? null} />;
}
