import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import OrganizationDataRequests from "@/app/components/settings/OrganizationDataRequests";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsOrgDataRequests"),
    ]);
    return {
        title: tNav("groupDataRequests"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical organization Data requests destination (#1340 PR 6).
 *
 * The request list is filtered and paged in the browser, as it is on its own route, so nothing is
 * read here; the route's layout has already established that the reader may be here.
 */
export default function OrganizationDataRequestsPage() {
    return <OrganizationDataRequests />;
}
