import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import PersonalWorkspaces from "@/app/components/settings/PersonalWorkspaces";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsPersonalWorkspaces"),
    ]);
    return {
        title: tNav("groupWorkspacesInvitations"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical Workspaces & invitations destination (#1340 WS4.3).
 *
 * Nothing is read here. The panel resolves the reader's pending invitations in the browser and keeps
 * them keyed on the active workspace, so accepting one and switching into it re-reads the list
 * rather than leaving an accepted invitation sitting on the page.
 */
export default function PersonalWorkspacesPage() {
    return <PersonalWorkspaces />;
}
