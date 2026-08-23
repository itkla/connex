import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import CrmConfiguration from "@/app/components/settings/CrmConfiguration";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getApprovalPolicies, getCurrentUserResultFromCookie } from "@/app/lib/api";
import type { ApprovalPolicy } from "@/app/lib/types";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsCrm"),
    ]);
    return {
        title: tNav("groupCrmConfiguration"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical CRM configuration destination (#1340 WS4.4): the workspace's one place for what a
 * record can hold, how a contact is judged against it, what needs signing off, and where the
 * automation that acts on all three lives.
 *
 * The approval policies are read here rather than in the client, matching how
 * `/records/approval-policies` already loads them. A failure of that read is not a refusal of the
 * destination — the other sections are unaffected and still render — but it is emphatically not a
 * workspace without policies either. It yields `null`, which the section reports as an unavailable
 * read the viewer can retry. Handing the browser an empty list instead would state, under a heading
 * about which documents need approval, that none of them do.
 */
export default async function CrmConfigurationPage() {
    const cookie = (await headers()).get("cookie");
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect("/auth/login");
    }

    let policies: ApprovalPolicy[] | null;
    try {
        policies = await getApprovalPolicies({
            headers: { cookie: cookie ?? "" },
            cache: "no-store",
        });
    } catch {
        policies = null;
    }

    return <CrmConfiguration policies={policies} />;
}
