import { expect, test } from "@playwright/test";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test("an already-read invitation expires in the open inbox without another fetch @mobile", async ({ page }) => {
    await useLocale(page, "en");
    const start = Date.now();
    await page.clock.install({ time: start - 60_000 });
    const asOf = new Date(start).toISOString();
    let listRequests = 0;
    let countRequests = 0;
    await page.route("**/api/notifications**", async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === "/api/notifications/counts") {
            countRequests += 1;
            await route.fulfill({ json: { unread: 0, snoozed: 0, stateVersion: 3, asOf, quietHoursActive: false } });
        } else if (url.pathname === "/api/notifications/facets") {
            await route.fulfill({ json: { categories: [], severities: [], workspaces: [], stateVersion: 3 } });
        } else if (url.pathname === "/api/notifications") {
            listRequests += 1;
            await route.fulfill({ json: {
                items: [{ id: 41, workspaceId: 9, workspaceName: "Expiry workspace", type: "workspace.join",
                    category: "workspace", severity: "info", templateVersion: 1, title: "Workspace invitation",
                    triggeredAt: asOf, readAt: asOf, createdAt: asOf, updatedAt: asOf,
                    invitationExpiresAt: new Date(start + 10_000).toISOString() }],
                total: 1, stateVersion: 3, asOf,
            } });
        } else {
            await route.continue();
        }
    });
    await page.goto("/notifications");
    const inbox = page.getByRole("region", { name: message("en", "notifications", "Notifications.inbox") });
    const invitation = inbox.locator('[data-notification-row="41"]');
    await expect(invitation).toBeVisible();
    await page.clock.pauseAt(start);
    await expect(page.getByRole("button", { name: message("en", "notifications", "Notifications.markAllRead") })).toBeDisabled();
    const initialListRequests = listRequests;
    const initialCountRequests = countRequests;
    await page.clock.runFor(9_999);
    await expect(invitation).toBeVisible();
    await page.clock.runFor(1);
    await expect(invitation).toHaveCount(0);
    await expect(inbox.getByText(message("en", "notifications", "Notifications.empty"))).toBeVisible();
    expect(listRequests).toBe(initialListRequests);
    expect(countRequests).toBe(initialCountRequests);
});
