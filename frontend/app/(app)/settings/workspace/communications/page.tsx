import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import WorkspaceCommunications from "@/app/components/settings/WorkspaceCommunications";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCapabilitiesResultFromCookie, getCurrentUserResultFromCookie } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsCommunications"),
    ]);
    return {
        title: tNav("groupCommunications"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical Communications destination (#1340 WS4.4): the workspace's one place for the mail it
 * sends from its own server, the provider its campaigns go out through, and the notification
 * defaults it does not yet get to set.
 *
 * Managed mail is resolved here rather than in the client, matching how `/settings/email` already
 * resolves it, so the email section knows on first paint whether there is anything to configure. A
 * failed capability read is not a managed instance and is not an unconfigured one: it yields `null`,
 * which the section reports as a retryable state. Answering `false` instead would offer an
 * administrator a form whose saves the instance may simply ignore.
 */
export default async function WorkspaceCommunicationsPage() {
    const cookie = (await headers()).get("cookie");
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect("/auth/login");
    }

    const capabilities = await getCapabilitiesResultFromCookie(cookie);

    return <WorkspaceCommunications mailManaged={capabilities.ok ? capabilities.data.mailManaged : null} />;
}
