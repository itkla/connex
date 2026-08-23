"use client";

import { useTranslations } from "next-intl";

import DiagnosticsPanel from "@/app/components/diagnostics/DiagnosticsPanel";
import OrgAuditPanel from "@/app/components/organization/OrgAuditPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { SettingsSectionRegion } from "@/app/components/settings/SettingsSectionRegion";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS } from "@/app/lib/organizationSettingsSections";

/**
 * The organization's Audit & diagnostics: what happened above its workspaces, and whether the
 * machinery behind them is healthy (#1340 PR 6).
 *
 * The same two jobs the workspace destination holds, one scope up, and deliberately under the same
 * group name: §7 asks that duplicated concepts be scope-labeled rather than renamed, and the
 * settings navigation labels this one by the scope it sits under. The description says which
 * organization, so a reader who arrived by deep link is not left inferring the scope from the URL.
 *
 * Unlike the workspace page, neither section needs a permission of its own. Both reads are gated on
 * organization standing and nothing else, which the route's layout resolves before either renders,
 * so there is no refusal to explain in place and no any-of loosening to justify. The audit panel
 * reads its own entries client-side, as it does on its own route; the diagnostics panel has no name
 * of its own, so this page supplies the heading its deep link is advertised under.
 */
export default function OrganizationAuditDiagnostics() {
    const t = useTranslations("SettingsOrgAuditDiagnostics");
    const tNav = useTranslations("SettingsNav");
    const tOrg = useTranslations("Organization");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(ORGANIZATION_AUDIT_DIAGNOSTICS_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupAuditDiagnostics")}
                    description={t("description", {
                        organization: activeWorkspace?.orgName ?? "",
                    })}
                />
            </Rise>

            <SettingsSectionRegion section="audit" arrived={arrived} register={register}>
                <OrgAuditPanel presentation="section" />
            </SettingsSectionRegion>

            <SettingsSectionRegion section="diagnostics" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tOrg("tabDiagnostics")}
                        description={t("diagnosticsDescription")}
                    >
                        <DiagnosticsPanel scope="organization" />
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>
        </div>
    );
}
