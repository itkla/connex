import { expect, test } from "@playwright/test";

import { activeWorkspaceId, registerUser } from "./support/api";
import { message } from "./support/messages";

for (const locale of ["en", "ja"] as const) {
    test(`standing approval organization controls stay available with authoring off ${locale} @mobile`, async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const name = `standing${Date.now().toString(36)}${testInfo.retry}${locale}`;
        const context = await browser.newContext({ baseURL, locale: locale === "ja" ? "ja-JP" : "en-US",
            storageState: { cookies: [], origins: [] }, reducedMotion: "reduce",
            viewport: testInfo.project.use.viewport });
        try {
            await registerUser(context.request, { username: name, password: `Standing!${name}A1`,
                email: `${name}@example.com` });
            const workspaceId = await activeWorkspaceId(context.request);
            await context.addCookies([{ name: "NEXT_LOCALE", value: locale, url: baseURL }]);
            const page = await context.newPage();
            await page.goto("/settings/organization/ai-governance");
            const panel = page.getByRole("region", { name: message(locale, "organization", "OrgStandingApproval.title") });
            await expect(panel).toBeVisible();
            await expect(panel.getByText(message(locale, "organization", "OrgStandingApproval.unavailable"))).toBeVisible();
            await expect(panel.getByRole("button", { name: message(locale, "organization", "OrgStandingApproval.stop"), exact: true })).toBeEnabled();
            await expect(panel.getByRole("link")).toHaveCount(0);
            const read = await context.request.get(`/api/ai/standing-approval-policy?workspaceId=${workspaceId}`);
            expect(read.status()).toBe(200);
            const policy: unknown = await read.json();
            if (typeof policy !== "object" || policy === null) throw new Error("Invalid policy response");
            expect(Reflect.get(policy, "enabled")).toBe(false);
            expect(Reflect.get(policy, "authoringEnabled")).toBe(false);
            await panel.getByRole("button", { name: message(locale, "organization", "OrgStandingApproval.stop"), exact: true }).click();
            await expect(page.getByText(message(locale, "organization", "OrgStandingApproval.stopped"), { exact: true })).toBeVisible();
            await expect(page.getByRole("heading", { name: message(locale, "organization", "OrgAi.title"), exact: true })).toBeVisible();
        } finally {
            await context.close();
        }
    });
}
