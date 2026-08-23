import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import OrganizationAuditDiagnostics from "@/app/components/settings/OrganizationAuditDiagnostics";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsOrgAuditDiagnostics"),
    ]);
    return {
        title: tNav("groupAuditDiagnostics"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical organization Audit & diagnostics destination (#1340 PR 6).
 *
 * Both panels read in the browser, keyed on the organization behind the active workspace, exactly
 * as they do on the two routes this destination consolidates. The workspace page reads its audit
 * entries on the server because `/admin/logs` did; the organization log never has, and moving it
 * here would change the surface's behavior in a PR that is meant to change only its address.
 */
export default function OrganizationAuditDiagnosticsPage() {
    return <OrganizationAuditDiagnostics />;
}
