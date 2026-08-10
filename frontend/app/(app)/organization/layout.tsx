import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import { PageHeader } from "@/app/components/PageHeader";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import OrgTabs from "@/app/components/organization/OrgTabs";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import {
    DEFAULT_CAPABILITIES,
    getCapabilities,
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
    const t = await getTranslations("Organization");
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
    const isOrgAdmin = activeWorkspace.orgRole !== null;
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    return (
        <PageShell tier="wide">
            <Rise>
                <PageHeader title={t("title")} description={t("subtitle")} />
            </Rise>
            <OrgTabs isOrgAdmin={isOrgAdmin} ssoEnabled={capabilities.sso} />
            <div>{isOrgAdmin ? children : <NoAccessCard />}</div>
        </PageShell>
    );
}
