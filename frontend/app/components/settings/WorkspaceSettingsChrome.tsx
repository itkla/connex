"use client";

import { usePathname } from "next/navigation";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SettingsTabs from "@/app/components/settings/SettingsTabs";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import { SETTINGS_HOME_ROUTE } from "@/app/lib/settingsManifest";

/**
 * The workspace-settings page chrome — the "Settings" header and the peer tab strip — for the
 * destinations that still live directly under `/settings` while #1340 migrates them to
 * `/settings/workspace/*`.
 *
 * The unified Settings home shares this route segment but not this chrome: it owns its own header
 * and renders the scope-grouped navigation that replaces the strip, so the chrome steps aside for
 * it rather than stacking a second header and a tab dump above it. This whole component dissolves
 * when the workspace destinations move and the strip is retired.
 *
 * @param title - the workspace-settings page title, resolved by the layout
 * @param description - the page description
 * @param mailManagementAvailability - whether managed mail is enabled, disabled, or unresolved
 */
export default function WorkspaceSettingsChrome({
    title,
    description,
    mailManagementAvailability,
}: {
    title: string;
    description: string;
    mailManagementAvailability: CapabilityAvailability;
}) {
    const pathname = usePathname() ?? "";
    if (pathname === SETTINGS_HOME_ROUTE) return null;
    return (
        <>
            <Rise>
                <PageHeader title={title} description={description} />
            </Rise>
            <SettingsTabs mailManagementAvailability={mailManagementAvailability} />
        </>
    );
}
