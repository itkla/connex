import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.use({ storageState: { cookies: [], origins: [] } });

function positiveId(body: Record<string, unknown>): number {
    const id = body.id;
    if (typeof id !== "number" || !Number.isInteger(id) || id <= 0) throw new Error("Expected an id");
    return id;
}

for (const locale of ["en", "ja"] as const) {
    test(`saved campaign revisions stay inspectable and unknown tokens are refused (${locale}) @mobile`, async ({ page }) => {
        const key = randomUUID();
        await registerUser(page.request, {
            username: `content_${key.replaceAll("-", "")}`,
            password: `Campaign!${key}A1`, email: `content-${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, workspaceId, csrf);
        const headers = { "X-Workspace-Id": String(workspaceId), [csrf.headerName]: csrf.token };
        const campaignId = positiveId(await seed.post("/api/campaigns", { name: `Content ${key}`, type: "email" }));
        const base = `/api/campaigns/${campaignId}/messages`;
        const messageId = positiveId(await seed.post(base, { name: "Saved content", channel: "email" }));
        const subject = "保存された件名 & exact source";
        const html = '</textarea><script>document.documentElement.dataset.campaignContentExecuted="yes"</script>\n<img src="https://campaign-preview.invalid/pixel"><p>本文 &amp; {{unsubscribe_url}}</p>';
        const text = "原文\n{{unsubscribe_url}}";
        await seed.post(`${base}/${messageId}/revisions`, { locale, subject, bodyHtml: html, bodyText: text });
        for (const field of ["subject", "bodyHtml", "bodyText"]) {
            const refused = await page.request.post(`${base}/${messageId}/revisions`, {
                headers, data: { locale, subject, bodyHtml: html, bodyText: text, [field]: "{{first_name}}" },
            });
            expect(refused.status()).toBe(400);
            expect(await refused.json()).toMatchObject({ code: "CAMPAIGN_UNSUPPORTED_TOKEN" });
        }
        const smsId = positiveId(await seed.post(base, { name: "SMS content", channel: "sms" }));
        const refusedSms = await page.request.post(`${base}/${smsId}/revisions`, {
            headers, data: { locale, bodyText: "{{first_name}}" },
        });
        expect(refusedSms.status()).toBe(400);

        const externalLoads: string[] = [];
        page.on("request", (request) => {
            if (request.url().startsWith("https://campaign-preview.invalid/")) externalLoads.push(request.url());
        });
        await useLocale(page, locale);
        await page.goto(`/marketing/campaigns/${campaignId}`);
        await page.getByRole("tab", { name: message(locale, "campaigns", "CampaignDetail.tabDelivery"), exact: true }).click();
        await page.getByRole("button", { name: /Saved content/ }).click();
        const label = (key: string) => message(locale, "campaigns", `CampaignMessages.${key}`);
        const inspect = (version: number) => page.getByRole("button", {
            name: label("inspectRevision").replace("{version}", String(version)), exact: true,
        });
        await inspect(1).click();
        const sheet = page.getByRole("dialog").filter({ has: page.locator("#saved-revision-html") });
        await expect(sheet.getByLabel(label("subject"), { exact: true })).toHaveValue(subject);
        await expect(sheet.getByLabel(label("bodyHtml"), { exact: true })).toHaveValue(html);
        await expect(sheet.getByLabel(label("bodyHtml"), { exact: true })).toHaveAttribute("readonly", "");
        await expect(sheet.getByLabel(label("bodyText"), { exact: true })).toHaveValue(text);
        await expect(sheet.locator("script, img, iframe")).toHaveCount(0);
        await sheet.getByRole("button", { name: label("startFromRevision"), exact: true }).click();
        await expect(sheet).not.toBeVisible();
        await expect(page.locator("#revision-subject")).toHaveValue(subject);
        await expect(page.locator("#revision-body-html")).toHaveValue(html);
        await expect(page.locator("#revision-body-text")).toHaveValue(text);
        await expect(page.getByText(label("supportedTokens").replaceAll("'{{'", "{{").replaceAll("'}}'", "}}"), { exact: true })).toBeVisible();
        await page.locator("#revision-subject").fill("{{first_name}}");
        const refusedSave = page.waitForResponse((response) => response.url().endsWith(`${base}/${messageId}/revisions`) && response.request().method() === "POST");
        await page.getByRole("button", { name: label("saveRevision"), exact: true }).click();
        expect((await refusedSave).status()).toBe(400);
        await expect(page.locator("#revision-subject")).toHaveValue("{{first_name}}");
        await expect(page.getByText(label("unsupportedTokens").replaceAll("'{{'", "{{").replaceAll("'}}'", "}}"), { exact: true })).toBeVisible();
        await page.locator("#revision-subject").fill("Corrected subject");
        const saved = page.waitForResponse((response) => response.url().endsWith(`${base}/${messageId}/revisions`) && response.request().method() === "POST");
        await page.getByRole("button", { name: label("saveRevision"), exact: true }).click();
        expect((await saved).status()).toBe(201);
        await expect(page.locator("#revision-subject")).toHaveValue("Corrected subject");
        await expect(page.locator("#revision-body-html")).toHaveValue(html);
        await inspect(1).click();
        await sheet.getByRole("button", { name: label("startFromRevision"), exact: true }).click();
        const discard = page.getByRole("dialog", { name: message(locale, "common", "ConfirmDiscard.title"), exact: true });
        await discard.getByRole("button", { name: message(locale, "common", "ConfirmDiscard.keepEditing"), exact: true }).click();
        await expect(discard).not.toBeVisible();
        await sheet.getByRole("button", { name: label("closeInspection"), exact: true }).last().click();
        await expect(page.locator("#revision-subject")).toHaveValue("Corrected subject");
        await page.reload();
        await page.getByRole("tab", { name: message(locale, "campaigns", "CampaignDetail.tabDelivery"), exact: true }).click();
        await page.getByRole("button", { name: /Saved content/ }).click();
        await inspect(1).click();
        await expect(sheet.getByLabel(label("subject"), { exact: true })).toHaveValue(subject);
        await expect(sheet.getByLabel(label("bodyHtml"), { exact: true })).toHaveValue(html);
        await sheet.getByRole("button", { name: label("closeInspection"), exact: true }).last().click();
        await inspect(2).click();
        await expect(sheet.getByLabel(label("subject"), { exact: true })).toHaveValue("Corrected subject");
        await expect(sheet.getByLabel(label("bodyHtml"), { exact: true })).toHaveValue(html);
        expect(externalLoads).toEqual([]);
        expect(await page.locator("html").getAttribute("data-campaign-content-executed")).toBeNull();
    });
}
