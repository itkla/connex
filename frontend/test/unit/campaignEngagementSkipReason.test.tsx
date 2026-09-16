import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import CampaignEngagement from "@/app/components/marketing/campaigns/CampaignEngagement";
import type { CampaignEngagement as CampaignEngagementData } from "@/app/lib/types";
import english from "@/messages/en/campaigns.json";
import japanese from "@/messages/ja/campaigns.json";

vi.mock("@/app/components/marketing/campaigns/CampaignRecipientsDialog", () => ({
    default: () => null,
}));

const COUNTS = {
    totalRecipients: 1,
    dispatched: 0,
    delivered: 0,
    bounced: 0,
    complained: 0,
    unsubscribed: 0,
    failed: 0,
    skipped: 1,
    skipReasons: { not_dispatchable: 1 },
    eventCounts: {},
    deliveryReceiptsAvailable: false,
    deliveryRate: null,
    bounceRate: null,
    complaintRate: null,
};

const ENGAGEMENT: CampaignEngagementData = {
    ...COUNTS,
    campaignId: 7,
    channels: [{ channel: "email", deliveries: 1 }],
    sends: [{ ...COUNTS, sendId: 11, status: "paused", channel: "email" }],
};

describe("campaign admission refusal copy", () => {
    it.each([
        { locale: "en", catalog: english, label: "Send no longer active" },
        { locale: "ja", catalog: japanese, label: "配信が停止されました" },
    ])("shows the translated skip reason in $locale", ({ locale, catalog, label }) => {
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={catalog} timeZone="UTC">
                <CampaignEngagement
                    campaignId={7}
                    engagement={ENGAGEMENT}
                    canReadRecipients={false}
                    canReconcileRecipients={false}
                />
            </NextIntlClientProvider>,
        );

        expect(html).toContain(label);
        expect(html).not.toContain("not_dispatchable");
    });
});
