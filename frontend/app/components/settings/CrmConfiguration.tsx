"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import ApprovalPoliciesBrowser from "@/app/components/records/approval-policies/ApprovalPoliciesBrowser";
import CustomFieldsPanel from "@/app/components/settings/CustomFieldsPanel";
import QualificationCriteriaPanel from "@/app/components/settings/QualificationCriteriaPanel";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import {
    SectionNotYetAvailable,
    SectionRefusal,
    SettingsSectionRegion,
} from "@/app/components/settings/SettingsSectionRegion";
import { usePermissionCheck } from "@/app/hooks/usePermissions";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { CRM_SECTIONS } from "@/app/lib/crmSections";
import type { PermissionCheck } from "@/app/lib/permissionState";
import type { ApprovalPolicy } from "@/app/lib/types";
import { Button } from "@/components/ui/button";

/** The refusal postures for this page's sections, in its own voice for the unresolved case. */
function RefusedSection({ check }: { check: Exclude<PermissionCheck, "granted"> }) {
    const t = useTranslations("SettingsCrm");
    return (
        <SectionRefusal
            check={check}
            retryTitle={t("accessCheckFailedTitle")}
            retryBody={t("accessCheckFailedBody")}
        />
    );
}

/**
 * CRM configuration: the workspace's one destination for how its records behave (#1340 WS4.4).
 *
 * It consolidates three surfaces that shared no address and barely shared a shelf — the fields a
 * record can carry, the criteria a contact is judged against, and the approval policies that were
 * filed under Records rather than Settings — and gives workflow configuration the settings entry
 * #1340 requires it to have. Each is a section with its own deep link, and the page composes the
 * shipped panels rather than restating them, so every old home keeps behaving the same until the
 * legacy routes redirect here.
 *
 * The page itself is ungated, because the approval-policy browser it absorbed already is: that
 * surface renders for any member and refuses only its writes. The two sections whose *reads* need a
 * permission say so where they stand, rather than taking the destination down with them — a member
 * who can read approval policies still has a page worth opening.
 *
 * Where a panel already displays the name its deep link is advertised under it renders bare, and
 * where it does not the page supplies the heading and the panel steps its own headings down to
 * `h3`. Qualification and approval policies name themselves; custom fields names the three record
 * types instead, so its section name comes from here.
 *
 * @param policies - the workspace's approval policies, or null when that read failed
 */
export default function CrmConfiguration({ policies }: { policies: ApprovalPolicy[] | null }) {
    const t = useTranslations("SettingsCrm");
    const tNav = useTranslations("SettingsNav");
    const tFields = useTranslations("WorkspaceSettings");
    const tQualification = useTranslations("WorkspaceQualification");
    const tPolicies = useTranslations("ApprovalPoliciesBrowser");
    const tWorkflows = useTranslations("CommonSidebar");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(CRM_SECTIONS);
    const fields = usePermissionCheck("CUSTOM_FIELD_MANAGE");
    const qualification = usePermissionCheck("WORKSPACE_SETTINGS");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupCrmConfiguration")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <SettingsSectionRegion section="custom-fields" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tFields("tabCustomFields")}
                        description={t("customFieldsDescription")}
                    >
                        {fields === "granted" ? (
                            <CustomFieldsPanel presentation="section" />
                        ) : (
                            <RefusedSection check={fields} />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion section="qualification" arrived={arrived} register={register}>
                <Rise>
                    {qualification === "granted" ? (
                        <QualificationCriteriaPanel />
                    ) : (
                        <SettingsSection
                            title={tQualification("title")}
                            description={tQualification("description")}
                        >
                            <RefusedSection check={qualification} />
                        </SettingsSection>
                    )}
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion
                section="approval-policies"
                arrived={arrived}
                register={register}
            >
                <Rise>
                    {policies === null ? (
                        <SettingsSection
                            title={tPolicies("title")}
                            description={tPolicies("sectionDescription")}
                        >
                            <SettingsAvailabilityNotice
                                variant="inline"
                                state="retry"
                                title={t("approvalPoliciesFailedTitle")}
                                body={t("approvalPoliciesFailedBody")}
                            />
                        </SettingsSection>
                    ) : (
                        <ApprovalPoliciesBrowser policies={policies} presentation="section" />
                    )}
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion section="workflows" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tWorkflows("navWorkflows")}
                        description={t("workflowsDescription")}
                    >
                        <SectionNotYetAvailable
                            body={t("workflowsGapBody")}
                            action={
                                <Button asChild variant="outline" size="inline">
                                    <Link href="/workflows">{t("workflowsGapAction")}</Link>
                                </Button>
                            }
                        />
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>
        </div>
    );
}
