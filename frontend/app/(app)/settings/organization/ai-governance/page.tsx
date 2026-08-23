import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import OrganizationAiGovernance from "@/app/components/settings/OrganizationAiGovernance";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsOrgAi"),
    ]);
    return {
        title: tNav("groupAiDataGovernance"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical AI & data governance destination (#1340 PR 6).
 *
 * The provider panel reads its three payloads in the browser, keyed on the active workspace, as it
 * does on its own route; the route's layout has already established that the reader may be here.
 */
export default function OrganizationAiGovernancePage() {
    return <OrganizationAiGovernance />;
}
