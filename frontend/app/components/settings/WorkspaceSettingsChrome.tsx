"use client";

import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SettingsTabs from "@/app/components/settings/SettingsTabs";
import { SETTINGS_GROUPS, SETTINGS_HOME_ROUTE } from "@/app/lib/settingsManifest";

/**
 * The routes that own their own chrome: the unified Settings home, and every canonical scope-group
 * destination as it lands. Read from the manifest rather than listed here, so a group that moves in
 * a later PR steps out of the legacy chrome by existing, not by being remembered.
 */
const OWN_CHROME_ROUTES = new Set<string>([
    SETTINGS_HOME_ROUTE,
    ...SETTINGS_GROUPS.map((group) => group.route),
]);

/**
 * The workspace-settings page chrome — the "Settings" header and the peer tab strip — for the
 * destinations that still live directly under `/settings` while #1340 migrates them to
 * `/settings/workspace/*`.
 *
 * The unified Settings home shares this route segment but not this chrome: it owns its own header
 * and renders the scope-grouped navigation that replaces the strip, so the chrome steps aside for
 * it rather than stacking a second header and a tab dump above it. A migrated scope-group
 * destination is the same case one step further along: it is named for the job it owns rather than
 * for "Settings", and the strip it replaced must not reappear above it.
 *
 * After #1340 PR 8 it stands over exactly two pages — `/settings/general` and `/settings/data` —
 * because every other address under this segment now redirects. It stopped carrying the
 * managed-mail availability in the same commit: that prop existed to mark the email tab, and the
 * email tab moved to the Communications page, which explains the managed state where it stands
 * rather than as an icon on a strip. This whole component dissolves when those last two workspace
 * destinations move.
 *
 * It resolves its own two strings rather than taking them as props, which is what lets the layout
 * above it do no request-time work at all on a segment most of whose addresses exist only to
 * forward.
 */
export default function WorkspaceSettingsChrome() {
    const pathname = usePathname() ?? "";
    const t = useTranslations("WorkspaceSettings");
    if (OWN_CHROME_ROUTES.has(pathname)) return null;
    return (
        <>
            <Rise>
                <PageHeader title={t("title")} description={t("subtitle")} />
            </Rise>
            <SettingsTabs />
        </>
    );
}
