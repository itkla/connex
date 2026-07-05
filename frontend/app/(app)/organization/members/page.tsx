import { headers } from "next/headers";

import { getCurrentUserFromCookie } from "@/app/lib/api";
import OrgMembersPanel from "@/app/components/organization/OrgMembersPanel";

export default async function OrgMembersPage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    return <OrgMembersPanel currentUserId={user?.id ?? null} />;
}
