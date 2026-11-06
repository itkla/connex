import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ["en", "ja"] as const) {
    test(`My Work keeps empty history honest and caps recent personal tasks (${locale}) @mobile`, async ({ page, context }) => {
        test.setTimeout(180_000);
        const unique = randomUUID().replaceAll("-", "").slice(0, 12);
        await registerUser(context.request, {
            username: `history_${unique}`, password: `History!${unique}A1`, email: `history_${unique}@example.test`,
        });
        await useLocale(context, locale);
        await page.goto("/me");
        const history = page.getByRole("region", { name: message(locale, "me", "MePage.recentWork"), exact: true });
        await expect(history.getByText(message(locale, "me", "MeTimeline.emptyState"), { exact: true })).toBeVisible();
        const identityResponse = await context.request.get("/api/auth/me");
        expect(identityResponse.status()).toBe(200);
        const identity: unknown = await identityResponse.json();
        if (typeof identity !== "object" || identity === null || !("id" in identity) || typeof identity.id !== "number") {
            throw new Error("The authenticated identity has no id");
        }
        const workspaceId = await activeWorkspaceId(context.request);
        const seed = seeder(context.request, workspaceId, await csrfBootstrap(context.request));
        for (let index = 0; index < 26; index++) {
            await seed.post("/api/tasks", { description: `Personal history ${index}`, assignedToId: identity.id, completed: false });
        }
        await page.reload();
        await expect(history.locator("li")).toHaveCount(25);
        await expect(history.getByText("Personal history 25", { exact: true })).toBeVisible();
        await expect(history.getByText("Personal history 0", { exact: true })).toHaveCount(0);
        await expect(history.getByText(message(locale, "me", "MePage.recentWorkScope"), { exact: true })).toBeVisible();
    });
}
