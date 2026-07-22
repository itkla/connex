import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCampaignsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import CampaignsBrowser from "@/app/components/marketing/campaigns/CampaignsBrowser";

export default async function CampaignsPage() {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect("/auth/login");
    }

    const campaigns = await getCampaignsFromCookie(cookie);
    return <CampaignsBrowser campaigns={campaigns} />;
}
