"use client";

import { useTranslations } from "next-intl";

import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import UsersBrowser from "@/app/components/records/users/UsersBrowser";
import AllowedDomainsPanel from "@/app/components/settings/AllowedDomainsPanel";
import MembersPanel from "@/app/components/settings/MembersPanel";
import RolesPanel from "@/app/components/settings/RolesPanel";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import {
    SectionRefusal,
    SettingsSectionRegion,
} from "@/app/components/settings/SettingsSectionRegion";
import { usePermissionCheck } from "@/app/hooks/usePermissions";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { PEOPLE_SECTIONS } from "@/app/lib/peopleSections";
import type { PermissionCheck } from "@/app/lib/permissionState";
import type { User } from "@/app/lib/types";

/**
 * The refusal posture for a section whose read the backend gates, in this page's voice.
 *
 * The postures themselves are shared; what belongs to this page is the copy for the unresolved
 * case, which must name the lookup that failed. A member reading "we couldn't check feature
 * availability" under Roles would be told about the wrong thing entirely.
 */
function RefusedSection({ check }: { check: Exclude<PermissionCheck, "granted"> }) {
    const t = useTranslations("SettingsPeople");
    return (
        <SectionRefusal
            check={check}
            retryTitle={t("accessCheckFailedTitle")}
            retryBody={t("accessCheckFailedBody")}
        />
    );
}

/**
 * People & access: the workspace's one destination for who belongs here and what they may do
 * (#1340 WS4.3).
 *
 * It consolidates four surfaces that each answered part of one question from a different address:
 * the members roster and its invite journey, the roles a member can hold, the allowed domains that
 * were buried in the roster's tab strip, and the member directory that `/users` browsed separately.
 * Each is a section with its own deep link, and the page composes the shipped panels rather than
 * restating them, so both homes keep behaving the same until the legacy routes redirect here.
 *
 * **Every section displays the name its deep link is advertised under.** Settings search offers a
 * reader "Roles"; arriving at `#roles` must therefore show a heading that says Roles, which the
 * roles panel alone does not — it names its two halves. So where a panel already displays the
 * advertised name it renders bare (the roster's own first section is "Members"), and where it does
 * not, the page supplies the `SettingsSection` and the panel steps its own headings down to `h3`.
 * The page stays one coherent outline either way.
 *
 * The page itself is visible to any member, because the roster is. Its sections carry their own
 * gates, matching what the backend enforces: roles needs `ROLE_MANAGE` to read, allowed domains
 * needs `WORKSPACE_SETTINGS`, and every write inside the roster is refused by the server as it
 * always was.
 *
 * @param currentUserId - the viewer, so the roster can handle their own row differently
 * @param users - the workspace's members for the directory section, or null when that read failed
 */
export default function PeopleAccess({
    currentUserId,
    users,
}: {
    currentUserId: number | null;
    users: User[] | null;
}) {
    const t = useTranslations("SettingsPeople");
    const tNav = useTranslations("SettingsNav");
    const tMembers = useTranslations("WorkspaceMembers");
    const tRoles = useTranslations("WorkspaceRoles");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(PEOPLE_SECTIONS);
    const roles = usePermissionCheck("ROLE_MANAGE");
    const domains = usePermissionCheck("WORKSPACE_SETTINGS");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupPeopleAccess")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <SettingsSectionRegion section="members" arrived={arrived} register={register}>
                <MembersPanel currentUserId={currentUserId} presentation="consolidated" />
            </SettingsSectionRegion>

            <SettingsSectionRegion section="roles" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection title={tRoles("title")} description={tRoles("subtitle")}>
                        {roles === "granted" ? (
                            <RolesPanel presentation="section" />
                        ) : (
                            <RefusedSection check={roles} />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion section="allowed-domains" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tMembers("domainsTitle")}
                        description={tMembers("domainsSubtitle")}
                    >
                        {domains === "granted" ? (
                            <AllowedDomainsPanel presentation="section" />
                        ) : (
                            <RefusedSection check={domains} />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>

            <SettingsSectionRegion section="directory" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection title={t("directoryTitle")} description={t("directoryDescription")}>
                        <div
                            id="member-detail"
                            ref={register("member-detail")}
                            tabIndex={-1}
                            className="scroll-mt-24 outline-none"
                        >
                            {users === null ? (
                                <SettingsAvailabilityNotice
                                    variant="inline"
                                    state="retry"
                                    title={t("directoryFailedTitle")}
                                    body={t("directoryFailedBody")}
                                />
                            ) : (
                                <UsersBrowser users={users} presentation="section" />
                            )}
                        </div>
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>
        </div>
    );
}
