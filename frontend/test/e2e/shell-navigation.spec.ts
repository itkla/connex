import { expect, test } from "@playwright/test";

import {
    activeWorkspaceId,
    csrfBootstrap,
    registerUser,
} from "./support/api";
import { message } from "./support/messages";

function profilePictureUrl(value: unknown): string {
    if (
        typeof value !== "object"
        || value === null
        || !("profilePictureUrl" in value)
        || typeof value.profilePictureUrl !== "string"
    ) {
        throw new Error("Profile-picture upload returned no managed URL");
    }
    return value.profilePictureUrl;
}

test.describe("authenticated shell navigation", () => {
    test("protected avatars are single-flight, warm, scoped, and fail safely", async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `media${Date.now().toString(36)}${testInfo.retry}`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        let releaseMedia: () => void = () => undefined;
        try {
            await registerUser(context.request, {
                username: runId,
                password: `Media!${runId}A1`,
                email: `${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(context.request);
            const csrf = await csrfBootstrap(context.request);
            const upload = await context.request.put("/api/users/me/profile-picture", {
                headers: {
                    "X-Workspace-Id": String(workspaceId),
                    [csrf.headerName]: csrf.token,
                },
                multipart: {
                    file: {
                        name: "avatar.png",
                        mimeType: "image/png",
                        buffer: Buffer.from(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                            "base64",
                        ),
                    },
                },
            });
            expect(upload.status()).toBe(200);
            const managedUrl = profilePictureUrl(await upload.json());
            const page = await context.newPage();
            let requestCount = 0;
            let capturedWorkspaceHeader: string | undefined;
            let failMedia = false;
            const mediaGate = new Promise<void>((resolve) => {
                releaseMedia = resolve;
            });

            await page.route(`**${managedUrl}`, async (route) => {
                requestCount += 1;
                capturedWorkspaceHeader = route.request().headers()["x-workspace-id"];
                if (failMedia) {
                    await route.fulfill({ status: 404, body: "" });
                    return;
                }
                const response = await route.fetch();
                await mediaGate;
                await route.fulfill({ response });
            });

            await page.goto("/dashboard");
            await expect.poll(() => requestCount).toBe(1);
            await page.locator("#app-sidebar").getByRole("button", { name: /^E2E Harness/ }).click();
            await page.getByRole("link", {
                name: message("en", "common", "CommonSidebar.profile"),
                exact: true,
            }).click();
            await expect(page).toHaveURL(/\/me$/);
            await expect.poll(() => requestCount).toBe(1);
            expect(capturedWorkspaceHeader).toBe(String(workspaceId));

            releaseMedia();
            await expect(page.locator('img[src^="blob:"]').first()).toBeVisible();
            await page.getByRole("link", {
                name: message("en", "common", "CommonSidebar.navDashboard"),
                exact: true,
            }).click();
            await page.locator("#app-sidebar").getByRole("button", { name: /^E2E Harness/ }).click();
            await page.getByRole("link", {
                name: message("en", "common", "CommonSidebar.profile"),
                exact: true,
            }).click();
            await expect(page).toHaveURL(/\/me$/);
            expect(requestCount).toBe(1);

            failMedia = true;
            await page.reload();
            await expect.poll(() => requestCount).toBe(2);
            const profileMenu = page.locator("#app-sidebar").getByRole("button", { name: /^E2E Harness/ });
            await expect(profileMenu).toBeVisible();
            await expect(profileMenu.locator("img")).toHaveCount(0);
        } finally {
            releaseMedia();
            await context.close();
        }
    });
});
