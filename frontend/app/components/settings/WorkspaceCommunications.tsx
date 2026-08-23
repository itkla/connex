"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import DeliveryPanel from "@/app/components/settings/DeliveryPanel";
import EmailPanel from "@/app/components/settings/EmailPanel";
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
import { COMMUNICATIONS_SECTIONS } from "@/app/lib/communicationsSections";
import type { PermissionCheck } from "@/app/lib/permissionState";
import { Button } from "@/components/ui/button";

/** The refusal postures for this page's sections, in its own voice for the unresolved case. */
function RefusedSection({ check }: { check: Exclude<PermissionCheck, "granted"> }) {
    const t = useTranslations("SettingsCommunications");
    return (
        <SectionRefusal
            check={check}
            retryTitle={t("accessCheckFailedTitle")}
            retryBody={t("accessCheckFailedBody")}
        />
    );
}

/**
 * Communications: the workspace's one destination for the mail it sends (#1340 WS4.4).
 *
 * It consolidates the two settings routes that each owned half of one question — the workspace's
 * own sending server, and the provider its campaigns and messages go out through — and gives the
 * third job #1340 names here an address it never had. Each is a section with its own deep link, and
 * the page composes the shipped panels rather than restating them, so both homes keep behaving the
 * same until the legacy routes redirect here.
 *
 * **Every section displays the name its deep link is advertised under, in every state.** That is
 * why the two panels hand their headings up to the page instead of drawing them: the email section
 * must still say "Email" when a managed instance leaves it nothing to configure, and a heading that
 * lives inside the panel disappears exactly when it is most needed. #1401 established that a
 * capability-managed destination explains itself where its name is rather than teleporting; this
 * page keeps that promise one level down, at the section.
 *
 * The whole destination needs `WORKSPACE_SETTINGS`, which is what both reads behind it need, so the
 * navigation does not offer it to a member who would find nothing but refusals. A reader who
 * arrives by URL is still told, per section, which refusal it is.
 *
 * @param mailManaged - whether the instance sends mail itself, or null when that lookup failed
 */
export default function WorkspaceCommunications({
    mailManaged,
}: {
    mailManaged: boolean | null;
}) {
    const t = useTranslations("SettingsCommunications");
    const tNav = useTranslations("SettingsNav");
    const tEmail = useTranslations("WorkspaceEmail");
    const tDelivery = useTranslations("WorkspaceDelivery");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(COMMUNICATIONS_SECTIONS);
    const settings = usePermissionCheck("WORKSPACE_SETTINGS");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupCommunications")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <SettingsSectionRegion section="email" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection title={tEmail("title")} description={tEmail("subtitle")}>
                        {settings !== "granted" ? (
                            <RefusedSection check={settings} />
                        ) : mailManaged === null ? (
                            <SettingsAvailabilityNotice variant="inline" state="retry" />
                        ) : mailManaged ? (
                            <SettingsAvailabilityNotice variant="inline" state="managed" />
                        ) : (
                            <EmailPanel presentation="section" />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion section="delivery" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tDelivery("title")}
                        description={tDelivery("subtitle")}
                    >
                        {settings === "granted" ? (
                            <DeliveryPanel presentation="section" />
                        ) : (
                            <RefusedSection check={settings} />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion
                section="notification-defaults"
                arrived={arrived}
                register={register}
            >
                <Rise>
                    <SettingsSection
                        title={t("notificationDefaultsTitle")}
                        description={t("notificationDefaultsDescription")}
                    >
                        <SectionNotYetAvailable
                            body={t("notificationDefaultsGapBody")}
                            action={
                                <Button asChild variant="outline" size="inline">
                                    <Link href="/account/notifications">
                                        {t("notificationDefaultsGapAction")}
                                    </Link>
                                </Button>
                            }
                        />
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>
        </div>
    );
}
