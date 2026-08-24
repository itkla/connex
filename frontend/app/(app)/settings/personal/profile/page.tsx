import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import PersonalProfile from "@/app/components/settings/PersonalProfile";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const [tAccount, t] = await Promise.all([
        getTranslations("Account"),
        getTranslations("SettingsPersonalProfile"),
    ]);
    return {
        title: tAccount("tabProfile"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical personal Profile destination (#1340 WS4.1).
 *
 * The account is resolved here, exactly as `/account/profile` resolved it, because the form is
 * seeded from it rather than fetching itself. A failed read is not an absent account: it yields the
 * shared unavailable page rather than an empty form whose save would overwrite fields it never
 * loaded. An account that resolves to nothing is a signed-out reader, who is sent to sign in.
 */
export default async function PersonalProfilePage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect("/auth/login");
    }

    return <PersonalProfile user={user} />;
}
