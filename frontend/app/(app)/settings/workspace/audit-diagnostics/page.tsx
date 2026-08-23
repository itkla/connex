import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect, unstable_rethrow } from "next/navigation";
import { getTranslations } from "next-intl/server";

import AuditDiagnostics, { type AuditRead } from "@/app/components/settings/AuditDiagnostics";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getAuditLogs, getCurrentUserResultFromCookie } from "@/app/lib/api";
import { loadCollection } from "@/app/lib/recordAccess";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsAuditDiagnostics"),
    ]);
    return {
        title: tNav("groupAuditDiagnostics"),
        description: t("metaDescription"),
    };
}

const PAGE_SIZE = 200;

/**
 * The canonical workspace Audit & diagnostics destination (#1340 WS4.4): what happened in this
 * workspace, and whether the machinery behind it is healthy.
 *
 * The audit entries are read here, as they were on `/admin/logs`, and the read's three outcomes stay
 * three outcomes. A refusal names who can lift it. A failure offers a retry. Neither is allowed to
 * arrive as an empty log, because on a security surface an empty list is a positive claim that
 * nothing happened — and unlike the standalone route, a thrown failure here would take the
 * diagnostics section down with it for no reason of its own.
 *
 * The catch is narrower than it looks: {@link loadCollection} sends an unauthenticated caller to
 * sign in by throwing, and swallowing that would strand a signed-out reader on a page telling them
 * the audit log is temporarily unavailable. `unstable_rethrow` lets Next's own control-flow errors
 * back out before anything here treats them as a failed read.
 */
export default async function AuditDiagnosticsPage() {
    const cookie = (await headers()).get("cookie");
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect("/auth/login");
    }

    let audit: AuditRead;
    try {
        const access = await loadCollection(() =>
            getAuditLogs(
                { limit: PAGE_SIZE, offset: 0 },
                { headers: { cookie: cookie ?? "" }, cache: "no-store" },
            ),
        );
        audit = access.kind === "forbidden"
            ? { kind: "refused" }
            : { kind: "loaded", entries: access.items };
    } catch (error) {
        unstable_rethrow(error);
        audit = { kind: "unavailable" };
    }

    return <AuditDiagnostics audit={audit} pageSize={PAGE_SIZE} />;
}
