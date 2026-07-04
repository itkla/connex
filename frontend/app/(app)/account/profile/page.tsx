import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { getCurrentUserFromCookie } from "@/app/lib/api";
import ProfilePanel from "@/app/components/account/ProfilePanel";

export default async function AccountProfilePage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect("/auth/login");
    }

    return <ProfilePanel user={user} />;
}
