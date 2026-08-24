import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import WorkspaceDataPrivacy from "@/app/components/settings/WorkspaceDataPrivacy";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsWorkspaceDataPrivacy"),
    ]);
    return {
        title: tNav("groupDataPrivacy"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical Data & privacy destination (#1340 WS4.4).
 *
 * Nothing is read here. The import dialog resolves its own history and reports its own progress, and
 * an import that is already running is picked up by the panel rather than by this route.
 */
export default function WorkspaceDataPrivacyPage() {
    return <WorkspaceDataPrivacy />;
}
