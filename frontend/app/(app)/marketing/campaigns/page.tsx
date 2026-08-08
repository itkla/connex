import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import {
    getCampaigns,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
} from "@/app/lib/api";
import { loadCollection } from "@/app/lib/recordAccess";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import PermissionsUnavailablePage from "@/app/components/PermissionsUnavailablePage";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import CampaignsBrowser from "@/app/components/marketing/campaigns/CampaignsBrowser";

export default async function CampaignsPage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect("/auth/login");
    }

    const init = { headers: { cookie: cookie ?? "" }, cache: "no-store" } as const;
    const [access, effectivePermissions] = await Promise.all([
        loadCollection(() => getCampaigns(init)),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);

    if (access.kind === "forbidden") {
        const t = await getTranslations("CampaignsPage");
        return <AccessDeniedPage title={t("deniedTitle")} body={t("deniedBody")} />;
    }
    if (!effectivePermissions.ok) {
        return <PermissionsUnavailablePage />;
    }

    return (
        <CampaignsBrowser
            campaigns={access.items}
            canCreate={effectivePermissions.data.includes("CAMPAIGN_MANAGE")}
        />
    );
}
