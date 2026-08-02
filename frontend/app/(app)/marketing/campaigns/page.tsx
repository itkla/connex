import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { getCampaigns, getCurrentUserFromCookie } from "@/app/lib/api";
import { loadCollection } from "@/app/lib/recordAccess";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import CampaignsBrowser from "@/app/components/marketing/campaigns/CampaignsBrowser";

export default async function CampaignsPage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect("/auth/login");
    }

    const access = await loadCollection(() =>
        getCampaigns({ headers: { cookie: cookie ?? "" }, cache: "no-store" }),
    );

    if (access.kind === "forbidden") {
        const t = await getTranslations("CampaignsPage");
        return <AccessDeniedPage title={t("deniedTitle")} body={t("deniedBody")} />;
    }

    return <CampaignsBrowser campaigns={access.items} />;
}
