import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import { PageHeader } from "@/app/components/PageHeader";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import OrgTabs from "@/app/components/organization/OrgTabs";
import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import {
    getCapabilitiesResultFromCookie,
    getMyWorkspacesResultFromCookie,
} from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Organization");
    return {
        title: t("title"),
        description: t("subtitle"),
    };
}

export default async function OrganizationLayout({ children }: { children: React.ReactNode }) {
    const cookie = (await headers()).get("cookie");
    const [t, workspacesResult, capabilitiesResult] = await Promise.all([
        getTranslations("Organization"),
        getMyWorkspacesResultFromCookie(cookie),
        getCapabilitiesResultFromCookie(cookie),
    ]);
    if (!workspacesResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const activeWorkspace = workspacesResult.data.workspaces.find(
        (workspace) => workspace.id === workspacesResult.data.activeWorkspaceId,
    );
    if (!activeWorkspace) {
        return <WorkspaceUnavailablePage />;
    }
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    const isOrgAdmin = activeWorkspace.orgRole !== null;
    return (
        <OrganizationWorkspaceGuard workspaceId={activeWorkspace.id}>
            <PageShell tier="wide">
                <Rise>
                    <PageHeader title={t("title")} description={t("subtitle")} />
                </Rise>
                <OrgTabs isOrgAdmin={isOrgAdmin} ssoEnabled={capabilitiesResult.data.sso} />
                <div>{isOrgAdmin ? children : <NoAccessCard />}</div>
            </PageShell>
        </OrganizationWorkspaceGuard>
    );
}
