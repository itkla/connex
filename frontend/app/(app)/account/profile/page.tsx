import { headers } from "next/headers";
import { redirect } from "next/navigation";

import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie } from "@/app/lib/api";
import ProfilePanel from "@/app/components/account/ProfilePanel";

export default async function AccountProfilePage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect("/auth/login");
    }

    return <ProfilePanel user={user} />;
}
