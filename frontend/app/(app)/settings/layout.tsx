import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { PageShell } from "@/app/components/PageShell";
import WorkspaceSettingsChrome from "@/app/components/settings/WorkspaceSettingsChrome";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";
import { capabilityAvailability } from "@/app/lib/capabilityAvailability";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("WorkspaceSettings");
    return {
        title: t("title"),
        description: t("subtitle"),
    };
}

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
    const cookie = (await headers()).get("cookie");
    const [t, capabilitiesResult] = await Promise.all([
        getTranslations("WorkspaceSettings"),
        getCapabilitiesResultFromCookie(cookie),
    ]);
    const mailManagementAvailability = capabilityAvailability(
        capabilitiesResult.ok ? capabilitiesResult.data.mailManaged : null,
    );
    return (
        <PageShell>
            <WorkspaceSettingsChrome
                title={t("title")}
                description={t("subtitle")}
                mailManagementAvailability={mailManagementAvailability}
            />
            <div>{children}</div>
        </PageShell>
    );
}
