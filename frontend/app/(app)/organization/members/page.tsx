import { headers } from "next/headers";

import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie } from "@/app/lib/api";
import OrgMembersPanel from "@/app/components/organization/OrgMembersPanel";

export default async function OrgMembersPage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    return <OrgMembersPanel currentUserId={user?.id ?? null} />;
}
