"use client";

import { useTranslations } from "next-intl";

import MembershipPanel from "@/app/components/account/MembershipPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";

/**
 * Workspaces & invitations: which workspaces the reader belongs to, and which are asking (#1340
 * WS4.3).
 *
 * The whole of what `/account/invites` served, renamed to say what it actually holds. The epic is
 * explicit that pending invitations and leaving a workspace are not profile settings, and the tab
 * called "Invites" described only half of what was already on the page — accepting an invitation and
 * leaving a workspace are the two ends of the same membership, and the destination is now named for
 * both.
 *
 * The panel keeps its two headings, which are the jobs themselves; the page supplies the name they
 * share.
 */
export default function PersonalWorkspaces() {
    const t = useTranslations("SettingsPersonalWorkspaces");
    const tNav = useTranslations("SettingsNav");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupWorkspacesInvitations")}
                    description={t("description")}
                />
            </Rise>

            <MembershipPanel />
        </div>
    );
}
