"use client";

import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import WorkspaceIdentityPanel from "@/app/components/settings/WorkspaceIdentityPanel";
import { useWorkspace } from "@/app/hooks/useWorkspace";

/**
 * General: the workspace's own record (#1340 WS4.1).
 *
 * The whole of what `/settings/general` served, at the address the 2026-08-19 ruling gives it —
 * General mirrors the organization scope's landing group, and is where a reader looks first for what
 * this workspace is called.
 *
 * The panel keeps its own heading. "Workspace identity" names the thing it edits and "General" names
 * where that sits in the settings, so neither word stands in for the other and the page reads as one
 * outline rather than a title repeated at two sizes.
 *
 * No gate here. The panel has always resolved `WORKSPACE_SETTINGS` itself and keeps three answers
 * apart — granted, refused, and a lookup that did not resolve — so a permission lost while the page
 * is open is still answered where the reader is standing. The manifest records the same requirement,
 * which is what keeps the navigation from offering this destination to a member who would find
 * nothing but a refusal.
 */
export default function WorkspaceGeneral() {
    const t = useTranslations("SettingsWorkspaceGeneral");
    const tSettings = useTranslations("WorkspaceSettings");
    const { activeWorkspace } = useWorkspace();

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tSettings("tabGeneral")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <WorkspaceIdentityPanel />
        </div>
    );
}
