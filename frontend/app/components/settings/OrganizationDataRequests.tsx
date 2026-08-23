"use client";

import { useTranslations } from "next-intl";

import DataRequestsPanel from "@/app/components/organization/DataRequestsPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import { SettingsSectionRegion } from "@/app/components/settings/SettingsSectionRegion";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ORGANIZATION_DATA_REQUESTS_SECTIONS } from "@/app/lib/organizationSettingsSections";

/**
 * Data requests: what the people in this organization's records have asked it to do about their own
 * data (#1340 PR 6).
 *
 * This is a compliance surface, and §4 sanctions the statutory register on exactly these: the panel
 * says disclosure, correction, and cease of use because a request under the APPI is those things by
 * name, and softening the words here would make the tooling harder to operate rather than kinder to
 * read. The plain-language obligation belongs on the surfaces members meet, and is met there.
 *
 * The panel keeps its own name and its own controls, so the section carries no second heading.
 */
export default function OrganizationDataRequests() {
    const t = useTranslations("SettingsOrgDataRequests");
    const tNav = useTranslations("Organization");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(ORGANIZATION_DATA_REQUESTS_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("tabDataRequests")}
                    description={t("description", {
                        organization: activeWorkspace?.orgName ?? "",
                    })}
                />
            </Rise>

            <SettingsSectionRegion section="requests" arrived={arrived} register={register}>
                <DataRequestsPanel presentation="section" />
            </SettingsSectionRegion>
        </div>
    );
}
