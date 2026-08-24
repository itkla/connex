"use client";

import { Suspense } from "react";
import { useTranslations } from "next-intl";

import ConnectionsPanel from "@/app/components/account/ConnectionsPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import { CONNECTED_ACCOUNTS_SECTIONS } from "@/app/lib/connectedAccountsSections";
import type { PermissionsStatus } from "@/app/lib/permissionState";
import type { InstanceCapabilities } from "@/app/lib/types";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Connected accounts: the reader's own providers, and everything they may do with them (#1340
 * WS4.2).
 *
 * The whole of what `/account/connections` served, at the address the epic gives it. The panel is
 * composed exactly as it ships — it is already section-shaped, drawing no page shell of its own —
 * and it is composed rather than reimplemented because #60 owns the provider journey inside it.
 *
 * **The panel keeps its own name here, and that is a disclosed residual rather than a decision.**
 * Every other panel this epic consolidated either hands its heading up to the page or is named for
 * something the group is not, so no title is said twice. This one is named "Connected accounts" and
 * so is its group, and the seam that would let the page suppress the panel's heading — the
 * `presentation` prop the other consolidated panels carry — cannot be added here, because
 * `ConnectionsPanel.tsx` belongs to the concurrent provider-retention change. Adding it is a
 * one-line follow-up on whichever of the two lands second; nothing else about the composition
 * depends on it.
 *
 * **`#reviews` anchors the provider cards, not a queue.** The capture review queue is a panel of
 * this surface addressed by query and existing once per connected provider, so no single element
 * here *is* the reviews. The anchor resolves to the cards those queues open from, which is the
 * posture `member-detail` already takes on People & access: it lands the reader on the way in.
 *
 * The panel reads and writes the query string as the reader opens and closes its drawers, so it
 * needs a Suspense boundary of its own — the same one the retired route gave it, moved here with
 * it.
 *
 * @param capabilities - the instance's connected-account and capture switches
 * @param capabilitiesAvailability - whether any provider capability is on, off, or unresolved
 * @param effectivePermissions - the viewer's effective permission keys, empty when the lookup failed
 * @param permissionsStatus - whether that lookup resolved, so a refusal can say which one it is
 */
export default function PersonalConnectedAccounts({
    capabilities,
    capabilitiesAvailability,
    effectivePermissions,
    permissionsStatus,
}: {
    capabilities: InstanceCapabilities;
    capabilitiesAvailability: CapabilityAvailability;
    effectivePermissions: string[];
    permissionsStatus: PermissionsStatus;
}) {
    const t = useTranslations("AccountConnections");
    const { register } = useSectionArrival(CONNECTED_ACCOUNTS_SECTIONS);

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader title={t("title")} />
            </Rise>

            <div id="reviews" ref={register("reviews")} tabIndex={-1} className="scroll-mt-24 outline-none">
                <Suspense
                    fallback={(
                        <div className="grid gap-3" role="status">
                            <Skeleton className="h-28 w-full rounded-2xl" />
                            <Skeleton className="h-28 w-full rounded-2xl" />
                        </div>
                    )}
                >
                    <ConnectionsPanel
                        capabilities={capabilities}
                        capabilitiesAvailability={capabilitiesAvailability}
                        effectivePermissions={effectivePermissions}
                        permissionsStatus={permissionsStatus}
                    />
                </Suspense>
            </div>
        </div>
    );
}
