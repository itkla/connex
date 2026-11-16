import { headers, cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import {
    getCampaign,
    getCampaignAudienceFromCookie,
    getCampaignEngagement,
    getCampaignExports,
    getCampaignMessages,
    getCampaignSends,
    getCampaignSnapshots,
    getCapabilities,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    toResult,
} from "@/app/lib/api";
import { loadDetailSection } from "@/app/lib/loadDetailSection";
import { DetailSectionsProvider } from "@/app/hooks/useDetailSections";
import { detailSectionFailure } from "@/app/lib/detailSectionResult";
import { capabilityAvailability } from "@/app/lib/capabilityAvailability";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import CampaignDetail from "@/app/components/marketing/campaigns/CampaignDetail";
import AccessDeniedPage from "@/app/components/AccessDeniedPage";
import PermissionsUnavailablePage from "@/app/components/PermissionsUnavailablePage";
import { loadCollection, loadRecord } from "@/app/lib/recordAccess";
import { resolveCampaignAccess } from "@/app/lib/campaignAccess";

export default async function CampaignDetailPage({
    params,
}: {
    params: Promise<{ id: string }>;
}) {
    const cookie = (await headers()).get("cookie");
    const detailWorkspaceId = Number((await cookies()).get("connex_workspace")?.value) || undefined;
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
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
        messagesResult,
        sendsResult,
        exportsResult,
        engagement,
        effectivePermissions,
        capabilitiesResult,
    ] = await Promise.all([
        getCampaignAudienceFromCookie(id, cookie),
        loadCollection(() => getCampaignSnapshots(id, init)),
        loadDetailSection(() => getCampaignMessages(id, init)),
        loadDetailSection(() => getCampaignSends(id, init)),
        loadDetailSection(() => getCampaignExports(id, init)),
        getCampaignEngagement(id, init).catch(() => null),
        getEffectivePermissionsResultFromCookie(cookie),
        toResult(getCapabilities(init)),
    ]);
    if (!effectivePermissions.ok) {
        return <PermissionsUnavailablePage />;
    }

    const initialAudience =
        audienceResult.ok && audienceResult.data ? audienceResult.data : null;

    return (
        <DetailSectionsProvider key={`${detailWorkspaceId}:${id}`} recordId={id} workspaceId={detailWorkspaceId} initial={{ campaignMessages: messagesResult, campaignSends: sendsResult, campaignExports: exportsResult }}>
        <CampaignDetail
            campaign={campaignAccess.record}
            initialAudience={initialAudience}
            audienceUnavailable={!audienceResult.ok}
            initialSnapshots={snapshotsAccess.kind === "loaded" ? snapshotsAccess.items : []}
            snapshotsRestricted={snapshotsAccess.kind === "forbidden"}
            initialMessages={messagesResult.kind === "loaded" ? messagesResult.data : []}
            initialSends={sendsResult.kind === "loaded" ? sendsResult.data : []}
            initialExports={exportsResult.kind === "loaded" ? exportsResult.data : []}
            messagesFailure={detailSectionFailure(messagesResult)}
            sendsFailure={detailSectionFailure(sendsResult)}
            exportsFailure={detailSectionFailure(exportsResult)}
            initialEngagement={engagement}
            access={resolveCampaignAccess(effectivePermissions.data)}
            deliveryAvailability={capabilityAvailability(
                capabilitiesResult.ok ? capabilitiesResult.data.campaignDelivery : null,
            )}
        />
        </DetailSectionsProvider>
    );
}
