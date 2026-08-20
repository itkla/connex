import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import PeopleAccess from "@/app/components/settings/PeopleAccess";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie, getUsers } from "@/app/lib/api";
import type { User } from "@/app/lib/types";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsPeople"),
    ]);
    return {
        title: tNav("groupPeopleAccess"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical People & access destination (#1340 WS4.3): the workspace's one place for who
 * belongs here, what a role may do, who may join unasked, and where to look a member up.
 *
 * The directory's members are read here rather than in the client, matching how `/users` already
 * loads them. That read is member-visible, so a failure is not a refusal: the page keeps its other
 * sections and hands the directory an empty list rather than refusing the whole destination over a
 * section of it.
 */
export default async function PeopleAccessPage() {
    const cookie = (await headers()).get("cookie");
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect("/auth/login");
    }

    let users: User[] = [];
    try {
        users = await getUsers({ headers: { cookie: cookie ?? "" }, cache: "no-store" });
    } catch {
        users = [];
    }

    return <PeopleAccess currentUserId={currentUser.id} users={users} />;
}
