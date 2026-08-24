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
 * Synchronous apart from its metadata, and deliberately: most addresses under this segment exist
 * only to forward, so the shell is not assembled on the way to a redirect.
 *
 * It does not, on its own, turn those forwards into `308`s. Every address under this segment sits
 * behind authentication and answers with a `200` carrying a client-side redirect rather than a
 * `Location` header, because `loading.tsx` beside this file opens a Suspense boundary that flushes
 * the shell before the page resolves. That is #1440, which is tracked and whose named fix is to
 * generate `next.config.ts` redirects from the manifest so the forward happens before the segment
 * renders. The reader lands on the right destination either way; what a `308` would additionally
 * buy is a crawler and link-checker guarantee that no crawler is in a position to collect on an
 * authenticated route. The retired addresses outside this segment — `/account/*`, `/organization/*`,
 * `/users`, `/admin/logs`, and `/records/approval-policies` — do answer `308`.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
    return (
        <PageShell>
            <div>{children}</div>
        </PageShell>
    );
}
