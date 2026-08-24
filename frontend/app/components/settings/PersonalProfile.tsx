"use client";

import { useTranslations } from "next-intl";

import ProfilePanel from "@/app/components/account/ProfilePanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import type { User } from "@/app/lib/types";

/**
 * Profile: who the reader is, on every workspace they belong to (#1340 WS4.1).
 *
 * The whole of what `/account/profile` served, under the name the epic gives the group. Nothing
 * about the form changes; what changes is that the page is named for this job rather than for
 * "Account", so the heading a reader lands on says what they came to change.
 *
 * The panel no longer draws its own name. It used to, because its route's only heading was the
 * account shell's — an eyebrow reading "Profile" under an `h1` reading "Account" was the one thing
 * telling a reader which tab they were on. Here the `h1` says it, and a panel repeating the page
 * title immediately beneath it is the stutter this consolidation exists to remove.
 *
 * No gate. A person's own details are not a workspace permission, and the endpoints behind the form
 * are scoped to the caller's account rather than to a role.
 *
 * @param user - the signed-in account, resolved by the route before this renders
 */
export default function PersonalProfile({ user }: { user: User }) {
    const t = useTranslations("SettingsPersonalProfile");
    const tAccount = useTranslations("Account");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader title={tAccount("tabProfile")} description={t("description")} />
            </Rise>

            <ProfilePanel user={user} />
        </div>
    );
}
