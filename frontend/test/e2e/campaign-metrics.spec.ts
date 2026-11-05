import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { csrfBootstrap, seeder } from "./support/api";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

function numericField(body: Record<string, unknown>, key: string): number {
    const value = body[key];
    if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
        throw new Error(`Expected a positive ${key} in the campaign response`);
    }
    return value;
}

for (const locale of ["en", "ja"] as const) {
    test(`an unsent campaign reports preparation without claiming delivery (${locale}) @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, fixture.workspaceId, csrf);
        const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
        const key = randomUUID();
        const name = `Campaign metrics ${key}`;
        await seed.post("/api/persons", {
            name,
            email: `campaign-${key}@example.invalid`,
            companyId: fixture.companies.primary.id,
        });
        const campaign = await seed.post("/api/campaigns", { name, type: "email" });
        const campaignId = numericField(campaign, "id");
        const base = `/api/campaigns/${campaignId}`;
        const audience = await page.request.put(`${base}/audience`, {
            headers,
            data: {
                recordType: "person",
                channel: "email",
                purpose: "marketing",
                definition: { match: "all", conditions: [{ type: "field", field: "name", op: "equals", value: name }] },
            },
        });
        expect(audience.ok()).toBe(true);
        const snapshot = await seed.post(`${base}/audience/snapshot`, {});
        expect(snapshot.estimatedIncluded).toBe(1);
        const content = await seed.post(`${base}/messages`, { name: "Prepared message", channel: "email" });
        const messageId = numericField(content, "id");
        await seed.post(`${base}/messages/${messageId}/revisions`, {
            locale, subject: "Campaign metrics", bodyHtml: "<p>Prepared content</p>", bodyText: "Prepared content",
        });
        const send = await seed.post(`${base}/sends`, {
            snapshotVersion: numericField(snapshot, "version"), messageId, messageVersion: 1, purpose: "marketing",
        });
        expect(send.status).toBe("draft");
        const response = await page.request.get(`${base}/engagement`, { headers });
        expect(response.ok()).toBe(true);
        expect(await response.json()).toMatchObject({
            preparedRecipients: 1,
            totalRecipients: 0,
            dispatched: 0,
            delivered: 0,
            deliveryReceiptsAvailable: false,
            channels: [{ channel: "email", preparedRecipients: 1, dispatched: 0, delivered: 0, deliveries: 0, deliveryReceiptsAvailable: false }],
        });
        const sentRecipients = await page.request.get(`${base}/recipients?submitted=true&page=1&size=25`, { headers });
        expect(sentRecipients.ok()).toBe(true);
        expect(await sentRecipients.json()).toMatchObject({ items: [], total: 0 });

        await useLocale(page, locale);
        await page.goto(`/marketing/campaigns/${campaignId}`);
        const overview = page.getByRole("tabpanel", { name: message(locale, "campaigns", "CampaignDetail.tabOverview") });
        const prepared = overview.getByText(message(locale, "campaigns", "CampaignDetail.preparedRecipients"), { exact: true }).locator("..");
        await expect(prepared).toContainText("1");
        await expect(overview.getByText(message(locale, "campaigns", "CampaignDetail.preparedRecipientsHint"))).toBeVisible();
        await expect(overview).not.toContainText(locale === "en" ? "Recipients reached" : "到達数");
        await page.getByRole("tab", { name: message(locale, "campaigns", "CampaignDetail.tabEngagement"), exact: true }).click();
        const engagement = page.getByRole("tabpanel", { name: message(locale, "campaigns", "CampaignDetail.tabEngagement") });
        await expect(engagement.getByText(message(locale, "campaigns", "CampaignEngagement.channelPrepared").replace("{count}", "1"), { exact: true })).toBeVisible();
        await expect(engagement.getByText(message(locale, "campaigns", "CampaignEngagement.channelSent").replace("{count}", "0"), { exact: true })).toBeVisible();
        await expect(engagement.getByText(message(locale, "campaigns", "CampaignEngagement.channelDeliveryNotMeasured"), { exact: true })).toBeVisible();
        await expect(engagement).not.toContainText(message(locale, "campaigns", "CampaignEngagement.deliveries").replace("{count}", "1"));

        await seed.post(`${base}/sends`, {
            snapshotVersion: numericField(snapshot, "version"), messageId, messageVersion: 1, purpose: "marketing",
        });
        await page.reload();
        await page.getByRole("tab", { name: message(locale, "campaigns", "CampaignDetail.tabEngagement"), exact: true }).click();
        const drillLabel = message(locale, "campaigns", "CampaignEngagement.drillThrough")
            .replace("{count}", "2")
            .replace("{counter}", message(locale, "campaigns", "CampaignEngagement.recipients"));
        await page.getByRole("button", { name: drillLabel, exact: true }).click();
        const dialog = page.getByRole("dialog");
        await expect(dialog).toContainText(locale === "en" ? "2 message entries prepared" : "準備済みのメッセージ2件");
        await expect(dialog.getByRole("link", { name, exact: true })).toHaveCount(2);
        for (const number of [1, 2]) {
            const label = message(locale, "campaigns", "CampaignRecipients.sendLabel")
                .replace("{number}", String(number)).replace("{message}", "Prepared message");
            await expect(dialog.getByText(label, { exact: true })).toBeVisible();
        }
    });
}
