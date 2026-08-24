"use client";

import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import DataImportPanel from "@/app/components/settings/DataImportPanel";
import { useWorkspace } from "@/app/hooks/useWorkspace";

/**
 * Data & privacy: how relationship history that already exists elsewhere gets into this workspace
 * (#1340 WS4.4).
 *
 * The whole of what `/settings/data` served, under the group name the epic gives it. The 2026-08-19
 * ruling settled where import and export belong and put them here rather than under General, so this
 * destination is the workspace's answer to what comes in and on whose terms.
 *
 * The panel keeps its heading, which names the job — bringing existing history in — under a page
 * named for the category it belongs to.
 *
 * Ungated to read. The import itself needs the create permissions for every record type it writes,
 * which the panel and the endpoints behind it enforce; a member holding fewer sees the destination
 * and is refused the operation rather than being shown a category that appears not to exist.
 */
export default function WorkspaceDataPrivacy() {
    const t = useTranslations("SettingsWorkspaceDataPrivacy");
    const tNav = useTranslations("SettingsNav");
    const { activeWorkspace } = useWorkspace();

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupDataPrivacy")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <DataImportPanel />
        </div>
    );
}
