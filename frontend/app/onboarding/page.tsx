import { headers } from "next/headers";
import { redirect } from "next/navigation";

import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserFromCookie, getMyWorkspacesResultFromCookie } from "@/app/lib/api";
import OnboardingForm from "@/app/onboarding/OnboardingForm";

/**
 * Workspace creation / join entry for authenticated users with no memberships.
 * Membership is checked server-side so a leftover {@code connex_workspace} cookie
 * after revocation cannot flash this page before bouncing to the dashboard (#1108).
 */
export default async function OnboardingPage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect("/auth/login");
    }
    const workspacesResult = await getMyWorkspacesResultFromCookie(cookie);
    if (!workspacesResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const { workspaces } = workspacesResult.data;
    if (workspaces.length > 0) {
        redirect("/dashboard");
    }
    return <OnboardingForm />;
}
