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
    preparedRecipients: 1,
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
    channels: [{ channel: "email", preparedRecipients: 1, dispatched: 0, delivered: 0, deliveryReceiptsAvailable: false }],
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

describe("campaign preparation and delivery evidence", () => {
    it.each([
        { locale: "en", catalog: english, prepared: "6 prepared", sent: "0 sent", falseDelivery: "6 delivered" },
        { locale: "ja", catalog: japanese, prepared: "準備済み 6件", sent: "送信済み 0件", falseDelivery: "6件到達" },
    ])("keeps an unsent draft separate from delivery facts in $locale", ({ locale, catalog, prepared, sent, falseDelivery }) => {
        const counts = { ...COUNTS, preparedRecipients: 6, skipped: 0, skipReasons: {} };
        const draft: CampaignEngagementData = {
            ...counts,
            campaignId: 7,
            channels: [{ channel: "email", preparedRecipients: 6, dispatched: 0, delivered: 0, deliveryReceiptsAvailable: false }],
            sends: [{ ...counts, sendId: 11, status: "draft", channel: "email" }],
        };
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={catalog} timeZone="UTC">
                <CampaignEngagement campaignId={7} engagement={draft} canReadRecipients={false} canReconcileRecipients={false} />
            </NextIntlClientProvider>,
        );

        expect(html).toContain(catalog.CampaignEngagement.recipients);
        expect(html).toContain(catalog.CampaignEngagement.preparedRecipientsHint);
        expect(html).toContain(prepared);
        expect(html).toContain(sent);
        expect(html).toContain(catalog.CampaignEngagement.channelDeliveryNotMeasured);
        expect(html).not.toContain(falseDelivery);
    });

    it("keeps receipt availability separate for each channel", () => {
        const engagement: CampaignEngagementData = {
            ...ENGAGEMENT,
            deliveryReceiptsAvailable: true,
            channels: [
                { channel: "email", preparedRecipients: 6, dispatched: 4, delivered: 2, deliveryReceiptsAvailable: true },
                { channel: "sms", preparedRecipients: 3, dispatched: 3, delivered: 0, deliveryReceiptsAvailable: false },
            ],
        };
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale="en" messages={english} timeZone="UTC">
                <CampaignEngagement campaignId={7} engagement={engagement} canReadRecipients={false} canReconcileRecipients={false} />
            </NextIntlClientProvider>,
        );

        expect(html).toContain("4 sent");
        expect(html).toContain("2 delivered");
        expect(html).toContain("3 sent");
        expect(html).toContain(english.CampaignEngagement.channelDeliveryNotMeasured);
        expect(html).not.toContain("0 delivered");
    });
});
