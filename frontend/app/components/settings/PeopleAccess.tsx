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
import { usePermissionCheck } from "@/app/hooks/usePermissions";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { PEOPLE_SECTIONS, type PeopleSection } from "@/app/lib/peopleSections";
import type { PermissionCheck } from "@/app/lib/permissionState";
import type { User } from "@/app/lib/types";
import { cn } from "@/lib/utils";

/**
 * One addressable region of the page: the anchor a deep link resolves against, and the arrival mark
 * a reader who followed one needs in order to see which section answered them.
 *
 * The mark is a background wash rather than an outline. An outline on a settings section reads as a
 * validation state; a wash that recedes reads as "here", which is all it has to say.
 */
function PeopleSectionRegion({
    section,
    arrived,
    register,
    children,
}: {
    section: PeopleSection;
    arrived: string | null;
    register: (section: string) => (element: HTMLElement | null) => void;
    children: React.ReactNode;
}) {
    return (
        <div
            id={section}
            ref={register(section)}
            tabIndex={-1}
            data-arrived={arrived === section ? "" : undefined}
            className={cn(
                "-mx-3 scroll-mt-24 rounded-2xl px-3 py-3 outline-none transition-colors duration-(--motion-expressive) ease-calm motion-reduce:transition-none",
                arrived === section ? "bg-muted/50" : "bg-transparent",
            )}
        >
            {children}
        </div>
    );
}

/**
 * The refusal posture for a section whose read the backend gates.
 *
 * #1340's rule is that a managed destination never vanishes and never teleports: a member who
 * cannot read roles still sees that roles exist here and is told who can change that, rather than
 * finding a page with a hole in it. A failed permission lookup is kept apart from a refusal,
 * because "we could not check" and "you may not" are different things to be told.
 */
function RefusedSection({ check }: { check: Exclude<PermissionCheck, "granted"> }) {
    return <SettingsAvailabilityNotice variant="inline" state={check === "denied" ? "ask-admin" : "retry"} />;
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

            <PeopleSectionRegion section="members" arrived={arrived} register={register}>
                <MembersPanel currentUserId={currentUserId} presentation="consolidated" />
            </PeopleSectionRegion>

            <PeopleSectionRegion section="roles" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection title={tRoles("title")} description={tRoles("subtitle")}>
                        {roles === "granted" ? (
                            <RolesPanel presentation="section" />
                        ) : (
                            <RefusedSection check={roles} />
                        )}
                    </SettingsSection>
                </Rise>
            </PeopleSectionRegion>

            <PeopleSectionRegion section="allowed-domains" arrived={arrived} register={register}>
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
            </PeopleSectionRegion>

            <PeopleSectionRegion section="directory" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection title={t("directoryTitle")} description={t("directoryDescription")}>
                        <div
                            id="member-detail"
                            ref={register("member-detail")}
                            tabIndex={-1}
                            className="scroll-mt-24 outline-none"
                        >
                            {users === null ? (
                                <SettingsAvailabilityNotice variant="inline" state="retry" />
                            ) : (
                                <UsersBrowser users={users} presentation="section" />
                            )}
                        </div>
                    </SettingsSection>
                </Rise>
            </PeopleSectionRegion>
        </div>
    );
}
