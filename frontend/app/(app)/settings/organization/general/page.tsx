import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import OrganizationGeneral from "@/app/components/settings/OrganizationGeneral";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsOrgGeneral"),
    ]);
    return {
        title: tNav("groupOrganizationGeneral"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical organization General destination (#1340 PR 6).
 *
 * Nothing is read here. The panel this page composes has always resolved the organization's own
 * record in the browser, keyed on the active workspace so a switch re-reads it, and moving that to
 * the server would fetch an organization the reader may be about to leave. The route's layout has
 * already established that they may be here at all.
 */
export default function OrganizationGeneralPage() {
    return <OrganizationGeneral />;
}
