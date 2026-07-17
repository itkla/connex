import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import {
    getCampaignFromCookie,
    getCampaignAudienceFromCookie,
    getCampaignExports,
    getCampaignMessages,
    getCampaignSends,
    getCampaignSnapshots,
    getCurrentUserFromCookie,
    getEffectivePermissionsFromCookie,
} from "@/app/lib/api";
import {
    type CampaignAudience,
    type CampaignAudienceExport,
    type CampaignAudienceSnapshotSummary,
    type CampaignMessage,
    type CampaignSend,
} from "@/app/lib/types";
import CampaignDetail from "@/app/components/marketing/campaigns/CampaignDetail";

export default async function CampaignDetailPage({
    params,
}: {
    params: Promise<{ id: string }>;
}) {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect("/auth/login");
    }

    const id = Number((await params).id);
    if (!Number.isInteger(id) || id <= 0) {
        notFound();
    }

    const campaignResult = await getCampaignFromCookie(id, cookie);
    if (!campaignResult.ok) {
        notFound();
    }

    const init = { headers: { cookie: cookie ?? "" }, cache: "no-store" } as const;
    const [audienceResult, snapshots, messages, sends, exports, effectivePermissions]: [
        { ok: true; data: CampaignAudience | undefined } | { ok: false },
        CampaignAudienceSnapshotSummary[],
        CampaignMessage[],
        CampaignSend[],
        CampaignAudienceExport[],
        string[],
    ] = await Promise.all([
        getCampaignAudienceFromCookie(id, cookie),
        getCampaignSnapshots(id, init).catch(() => []),
        getCampaignMessages(id, init).catch(() => []),
        getCampaignSends(id, init).catch(() => []),
        getCampaignExports(id, init).catch(() => []),
        getEffectivePermissionsFromCookie(cookie),
    ]);

    const initialAudience =
        audienceResult.ok && audienceResult.data ? audienceResult.data : null;

    return (
        <CampaignDetail
            campaign={campaignResult.data}
            initialAudience={initialAudience}
            initialSnapshots={snapshots}
            initialMessages={messages}
            initialSends={sends}
            initialExports={exports}
            canManage={effectivePermissions.includes("CAMPAIGN_MANAGE")}
            canSend={effectivePermissions.includes("CAMPAIGN_SEND")}
        />
    );
}
