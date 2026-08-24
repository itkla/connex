import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { PageShell } from "@/app/components/PageShell";
import WorkspaceSettingsChrome from "@/app/components/settings/WorkspaceSettingsChrome";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("WorkspaceSettings");
    return {
        title: t("title"),
        description: t("subtitle"),
    };
}

/**
 * The `/settings` segment's shell.
 *
 * It stopped resolving instance capabilities in #1340 PR 8. The one capability it read was managed
 * mail, and it read it only to mark the email tab in the legacy strip; the email job now lives on
 * the Communications page, which resolves that capability itself and explains the managed state in
 * place. A layout fetch that no descendant reads is a request on every settings navigation paid for
 * nothing, so it went with the tab it served.
 *
 * {@link WorkspaceSettingsChrome} still stands over the two workspace destinations that have not
 * moved, and withholds itself from every route that owns its own header.
 */
export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("WorkspaceSettings");
    return (
        <PageShell>
            <WorkspaceSettingsChrome title={t("title")} description={t("subtitle")} />
            <div>{children}</div>
        </PageShell>
    );
}
