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
 * Synchronous on purpose. The one capability it used to resolve was managed mail, read only to mark
 * the email tab in the legacy strip; that job moved to the Communications page, which resolves the
 * capability itself and explains the managed state where it stands. The two strings the chrome
 * needs are now resolved by the chrome, which is a client component already reading that namespace.
 * What is left is a layout that does no request-time work, on a segment most of whose addresses
 * exist only to forward — so the shell is no longer assembled on the way to a redirect.
 *
 * It does not, on its own, turn those forwards into `308`s. Every address under this segment sits
 * behind authentication and answers with a `200` carrying a client-side redirect rather than a
 * `Location` header, which is how the five legacy `/settings` forwards already behaved before
 * #1340 PR 8. The reader lands on the right section either way; what a `308` would additionally buy
 * is a crawler and link-checker guarantee that no crawler is in a position to collect on an
 * authenticated route. The retired addresses outside this segment — `/organization/*`, `/users`,
 * `/admin/logs`, and `/records/approval-policies` — do answer `308`.
 *
 * {@link WorkspaceSettingsChrome} still stands over the two workspace destinations that have not
 * moved, and withholds itself from every route that owns its own header.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
    return (
        <PageShell>
            <WorkspaceSettingsChrome />
            <div>{children}</div>
        </PageShell>
    );
}
