import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getMyWorkspacesResultFromCookie } from "@/app/lib/api";

/**
 * The organization gate for the canonical organization destinations of #1340.
 *
 * The legacy `/organization` layout keeps its own copy of this, unchanged. These routes are not
 * under it — they are under `/settings`, whose layout knows only about the workspace — so the gate
 * has to be re-established here or five organization surfaces would be served to any signed-in
 * member. It is the same gate and the same source: the organization role the active workspace
 * carries, resolved on the server from the session cookie, with no role meaning no organization.
 *
 * A refusal takes #1340's `ask-admin` posture rather than the legacy access-denied card, because
 * these destinations declare that state in the manifest. It keeps the shipped organization copy,
 * which says the specific thing a general notice cannot: that this needs organization standing, not
 * a workspace role.
 *
 * The check is nullish rather than an identity test against `null`, and that is the whole gate. The
 * workspace payload omits `orgRole` entirely for a member who holds none — the field is typed
 * `OrgRole | null` but arrives absent — so `=== null` reads `undefined` and admits exactly the
 * viewer it exists to refuse. The legacy organization layout still tests identity and is wrong in
 * the same way; it is left alone here because #1340 does not touch those routes, and it fails
 * closed twice over regardless: every organization panel refuses a 403 in place and every
 * organization endpoint requires the role. That does not make the entry point honest, which is why
 * this one is fixed.
 *
 * {@link OrganizationWorkspaceGuard} stays, for the same reason it exists on the legacy routes: the
 * standing resolved here belongs to one workspace, and a switch to another must withhold the page
 * rather than let it keep rendering against authority the reader may no longer hold.
 */
export default async function OrganizationSettingsLayout({
    children,
}: {
    children: React.ReactNode;
}) {
    const cookie = (await headers()).get("cookie");
    const workspacesResult = await getMyWorkspacesResultFromCookie(cookie);
    if (!workspacesResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const activeWorkspace = workspacesResult.data.workspaces.find(
        (workspace) => workspace.id === workspacesResult.data.activeWorkspaceId,
    );
    if (!activeWorkspace) {
        return <WorkspaceUnavailablePage />;
    }
    if (activeWorkspace.orgRole == null) {
        const t = await getTranslations("Organization");
        return (
            <SettingsAvailabilityNotice
                state="ask-admin"
                title={t("noAccessTitle")}
                body={t("noAccessBody")}
            />
        );
    }
    return (
        <OrganizationWorkspaceGuard workspaceId={activeWorkspace.id}>
            {children}
        </OrganizationWorkspaceGuard>
    );
}
