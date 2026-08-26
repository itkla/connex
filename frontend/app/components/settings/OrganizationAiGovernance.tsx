"use client";

import { useTranslations } from "next-intl";

import OrgAiProviderPanel from "@/app/components/organization/OrgAiProviderPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import { SettingsSectionRegion } from "@/app/components/settings/SettingsSectionRegion";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ORGANIZATION_AI_GOVERNANCE_SECTIONS } from "@/app/lib/organizationSettingsSections";

/**
 * AI & data governance: the AI provider this organization brings, and the rules it holds it to
 * (#1340 PR 6).
 *
 * One shipped surface, under the name the epic gives its group. The group is wider than what ships
 * — governance beyond the provider's own controls has no settings home yet — but nothing here
 * claims otherwise: the destination holds the one job that exists, keeps it addressable under the
 * name it already has, and invents no empty section to fill out a title.
 *
 * The provider panel keeps its own name, so the section is not wrapped in a second heading that
 * would repeat it.
 */
export default function OrganizationAiGovernance() {
    const t = useTranslations("SettingsOrgAi");
    const tNav = useTranslations("SettingsNav");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(ORGANIZATION_AI_GOVERNANCE_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupAiDataGovernance")}
                    description={t("description", {
                        organization: activeWorkspace?.orgName ?? "",
                    })}
                />
            </Rise>

            <SettingsSectionRegion section="ai-provider" arrived={arrived} register={register}>
                <OrgAiProviderPanel presentation="section" />
            </SettingsSectionRegion>
        </div>
    );
}
