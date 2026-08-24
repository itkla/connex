"use client";

import { useTranslations } from "next-intl";

import SecurityPanel from "@/app/components/account/SecurityPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";

/**
 * Security: how the reader signs in (#1340 WS4.1).
 *
 * The whole of what `/account/security` served. The panel keeps its own heading, because it is named
 * for the thing it manages — passkeys — and the group is named for the job, which is signing in
 * safely. Those are different words, so both earn their place: the page says what this destination
 * is for, the section says what it currently offers to do about it.
 *
 * This is the shape every other single-section scope destination takes, and the reason the Profile
 * page does not take it: there the two names were the same word.
 */
export default function PersonalSecurity() {
    const t = useTranslations("SettingsPersonalSecurity");
    const tAccount = useTranslations("Account");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader title={tAccount("tabSecurity")} description={t("description")} />
            </Rise>

            <SecurityPanel />
        </div>
    );
}
