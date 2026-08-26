import { type CampaignPayload } from "@/app/lib/types";

/** The full-page campaign builder a campaign is authored in, and where instant-create lands. */
export function campaignBuilderPath(campaignId: number): string {
    return `/marketing/campaigns/${campaignId}`;
}

/**
 * What the instant-create prompt sends. Only the two facts the prompt asks for, plus the draft
 * status a new campaign starts in — the objective, budget, window, owner, and parent program are
 * the builder's job, so the create request must not invent them.
 */
export function campaignInstantCreatePayload(name: string, type: string): CampaignPayload {
    return {
        name: name.trim(),
        type: type.trim(),
        status: "draft",
    };
}
