import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import {
    DEFAULT_CAPABILITIES,
    getCampaign,
    getCampaignAudienceFromCookie,
    getCampaignEngagement,
    getCampaignExports,
    getCampaignMessages,
    getCampaignSends,
    getCampaignSnapshots,
    getCapabilities,
    getCurrentUserFromCookie,
    getEffectivePermissionsResultFromCookie,
    type CookieResult,
} from "@/app/lib/api";
import {
    type CampaignAudience,
    type CampaignAudienceExport,
    type CampaignAudienceSnapshotSummary,
    type CampaignEngagement,
    type CampaignMessage,
    type CampaignSend,
    type InstanceCapabilities,
} from "@/app/lib/types";
import CampaignDetail from "@/app/components/marketing/campaigns/CampaignDetail";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import PermissionsUnavailablePage from "@/app/components/PermissionsUnavailablePage";
import { loadCollection, loadRecord, type CollectionAccess } from "@/app/lib/recordAccess";
import { resolveCampaignAccess } from "@/app/lib/campaignAccess";

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

    const campaignInit = { headers: { cookie: cookie ?? "" }, cache: "no-store" } as const;
    const campaignAccess = await loadRecord(() => getCampaign(id, campaignInit));
    if (campaignAccess.kind === "forbidden") {
        return <AccessDeniedPage />;
    }
    if (campaignAccess.kind === "missing") {
        notFound();
    }

    const init = { headers: { cookie: cookie ?? "" }, cache: "no-store" } as const;
    const [
        audienceResult,
        snapshotsAccess,
        messages,
        sends,
        exports,
        engagement,
        effectivePermissions,
        capabilities,
    ]: [
        { ok: true; data: CampaignAudience | undefined } | { ok: false },
        CollectionAccess<CampaignAudienceSnapshotSummary>,
        CampaignMessage[],
        CampaignSend[],
        CampaignAudienceExport[],
        CampaignEngagement | null,
        CookieResult<string[]>,
        InstanceCapabilities,
    ] = await Promise.all([
        getCampaignAudienceFromCookie(id, cookie),
        loadCollection(() => getCampaignSnapshots(id, init)),
        getCampaignMessages(id, init).catch(() => []),
        getCampaignSends(id, init).catch(() => []),
        getCampaignExports(id, init).catch(() => []),
        getCampaignEngagement(id, init).catch(() => null),
        getEffectivePermissionsResultFromCookie(cookie),
        getCapabilities(init).catch(() => DEFAULT_CAPABILITIES),
    ]);
    if (!effectivePermissions.ok) {
        return <PermissionsUnavailablePage />;
    }

    const initialAudience =
        audienceResult.ok && audienceResult.data ? audienceResult.data : null;

    return (
        <CampaignDetail
            campaign={campaignAccess.record}
            initialAudience={initialAudience}
            initialSnapshots={snapshotsAccess.kind === "loaded" ? snapshotsAccess.items : []}
            snapshotsRestricted={snapshotsAccess.kind === "forbidden"}
            initialMessages={messages}
            initialSends={sends}
            initialExports={exports}
            initialEngagement={engagement}
            access={resolveCampaignAccess(effectivePermissions.data)}
            deliveryEnabled={capabilities.campaignDelivery}
        />
    );
}
