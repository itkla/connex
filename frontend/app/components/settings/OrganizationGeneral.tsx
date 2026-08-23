"use client";

import { useTranslations } from "next-intl";

import OrganizationOverviewPanel from "@/app/components/organization/OrganizationOverviewPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ORGANIZATION_GENERAL_SECTIONS } from "@/app/lib/organizationSettingsSections";

/**
 * General: the organization's own record (#1340 PR 6).
 *
 * It is the whole of what `/organization/overview` served, under the name the 2026-08-19 ruling
 * gives it — §7 retires "Overview" as a page name, and the shipped tab keeps its label only on the
 * legacy route it still titles. The content is unchanged, and the panel it composes is the shipped
 * one, so both homes behave identically until the legacy route redirects here.
 *
 * What the consolidation adds is addresses. The panel's three blocks answered three different
 * questions from one page that named none of them: what this organization is called, how its
 * workspaces and authority fit together, and how a workspace or the organization ends. Each now has
 * a deep link and a name in settings search. Because the panel owns those three headings, it also
 * owns the regions that make them arrivable; this page hands it the arrival registrar rather than
 * wrapping blocks it cannot see.
 *
 * The page carries no gate. Its route's layout resolves organization standing before anything here
 * renders, and the panel refuses again on its own if the backend disagrees.
 */
export default function OrganizationGeneral() {
    const t = useTranslations("SettingsOrgGeneral");
    const tNav = useTranslations("SettingsNav");
    const { activeWorkspace } = useWorkspace();
    const sections = useSectionArrival(ORGANIZATION_GENERAL_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupOrganizationGeneral")}
                    description={t("description", {
                        organization: activeWorkspace?.orgName ?? "",
                    })}
                />
            </Rise>

            <OrganizationOverviewPanel sections={sections} />
        </div>
    );
}
