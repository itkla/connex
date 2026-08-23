"use client";

import { useTranslations } from "next-intl";

import OrgAllowedDomainsPanel from "@/app/components/organization/OrgAllowedDomainsPanel";
import OrgMembersPanel from "@/app/components/organization/OrgMembersPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { SettingsSectionRegion } from "@/app/components/settings/SettingsSectionRegion";
import SsoPanel from "@/app/components/settings/SsoPanel";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ORGANIZATION_IDENTITY_SECTIONS } from "@/app/lib/organizationSettingsSections";

/**
 * Identity & administrators: who runs this organization, who may be invited into it, and how they
 * sign in (#1340 PR 6).
 *
 * Three tabs that each answered part of one question become three sections of one page, in that
 * order. The page composes the shipped panels, so the legacy routes keep behaving exactly as they
 * do until the redirects land.
 *
 * The write boundaries are unchanged and remain the panels' own. The roster's every mutation is
 * owner-only on the backend and the roster already renders nothing an administrator cannot do —
 * which is what the manifest records for that section as `orgWrite: "owner"`, against the
 * destination's own `admin` for the domain policy and the connection. This page adds no control of
 * its own and therefore no new gate to get wrong.
 *
 * Single sign-on is the one section whose existence depends on the deployment. An instance built
 * without it says so where the section stands rather than dropping the section or forwarding the
 * reader to the roster, which is the teleport #1340 forbids and which this surface used to perform.
 * A failed capability read is neither "on" nor "off": it offers a retry instead of a form whose
 * saves an instance without single sign-on would ignore.
 *
 * @param sso - whether the deployment has single sign-on, or null when that read failed
 * @param currentUserId - the viewer, so the roster can mark their own row
 */
export default function OrganizationIdentity({
    sso,
    currentUserId,
}: {
    sso: boolean | null;
    currentUserId: number | null;
}) {
    const t = useTranslations("SettingsOrgIdentity");
    const tNav = useTranslations("SettingsNav");
    const tOrg = useTranslations("Organization");
    const tSso = useTranslations("WorkspaceSso");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(ORGANIZATION_IDENTITY_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupIdentityAdministrators")}
                    description={t("description", {
                        organization: activeWorkspace?.orgName ?? "",
                    })}
                />
            </Rise>

            <SettingsSectionRegion section="administrators" arrived={arrived} register={register}>
                <OrgMembersPanel currentUserId={currentUserId} presentation="section" />
            </SettingsSectionRegion>

            <SettingsSectionRegion section="allowed-domains" arrived={arrived} register={register}>
                <OrgAllowedDomainsPanel presentation="section" />
            </SettingsSectionRegion>

            <SettingsSectionRegion section="sso" arrived={arrived} register={register}>
                {sso === true ? (
                    <SsoPanel presentation="section" />
                ) : (
                    <Rise>
                        <SettingsSection title={tOrg("tabSso")} description={tSso("subtitle")}>
                            {sso === false ? (
                                <SettingsAvailabilityNotice variant="inline" state="not-enabled" />
                            ) : (
                                <SettingsAvailabilityNotice
                                    variant="inline"
                                    state="retry"
                                    title={t("ssoCheckFailedTitle")}
                                    body={t("ssoCheckFailedBody")}
                                />
                            )}
                        </SettingsSection>
                    </Rise>
                )}
            </SettingsSectionRegion>
        </div>
    );
}
