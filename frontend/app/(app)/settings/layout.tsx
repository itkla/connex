import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { PageShell } from "@/app/components/PageShell";

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
 * All it does now is give the segment its page width. The header and peer-tab strip that used to
 * stand here went with their last destinations: `WorkspaceSettingsChrome` existed to put a title
 * reading "Settings" and a strip of nine tabs above the workspace pages that lived directly under
 * this segment, and #1340 has now moved every one of them to a canonical scope-group destination
 * that is named for the job it owns and draws its own heading. A second header saying "Settings"
 * above a page called "Communications" was exactly the stacking the epic set out to remove.
 *
 * Synchronous apart from its metadata. Fixed-target forwards are generated from the manifest in
 * `next.config.ts` and answer with true `308`s before routing (#1440). Only
 * `/settings/workflows/[legacyRuleId]`, whose target needs a lookup, still renders under this
 * segment's loading boundary; its streamed redirect is tracked in #2125.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
    return (
        <PageShell>
            <div>{children}</div>
        </PageShell>
    );
}
