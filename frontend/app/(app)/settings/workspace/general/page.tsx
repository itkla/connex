import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import WorkspaceGeneral from "@/app/components/settings/WorkspaceGeneral";

export async function generateMetadata(): Promise<Metadata> {
    const [tSettings, t] = await Promise.all([
        getTranslations("WorkspaceSettings"),
        getTranslations("SettingsWorkspaceGeneral"),
    ]);
    return {
        title: tSettings("tabGeneral"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical workspace General destination (#1340 WS4.1).
 *
 * Nothing is read here. The panel edits the active workspace the app shell already resolved, keyed
 * on its id so a switch re-seeds the form rather than leaving one workspace's name in a field that
 * would save to another.
 */
export default function WorkspaceGeneralPage() {
    return <WorkspaceGeneral />;
}
