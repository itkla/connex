import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import { PageHeader } from "@/app/components/PageHeader";
import OrgTabs from "@/app/components/organization/OrgTabs";
import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Organization");
    return {
        title: t("title"),
        description: t("subtitle"),
    };
}

export default async function OrganizationLayout({ children }: { children: React.ReactNode }) {
    const t = await getTranslations("Organization");
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    return (
        <PageShell tier="wide">
            <Rise>
                <PageHeader title={t("title")} description={t("subtitle")} />
            </Rise>
            <OrgTabs ssoEnabled={capabilities.sso} />
            <div>{children}</div>
        </PageShell>
    );
}
