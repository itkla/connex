import { expect, test } from "@playwright/test";

test.describe("notifications", () => {
    test("the inbox page renders its controls and read state", async ({ page }) => {
        await page.goto("/notifications");
        await expect(page.getByRole("heading", { name: "Notifications" })).toBeVisible();
        await expect(page.getByRole("button", { name: "Mark all read" })).toBeVisible();

        const inbox = page.getByRole("region", { name: "Inbox" });
        await expect(inbox).toBeVisible();
        await expect(inbox.getByText("You are all caught up.")).toBeVisible();
        await expect(page.getByRole("button", { name: "Mark all read" })).toBeDisabled();
    });

    test("the bell opens the notification popover with a link to the inbox", async ({ page }) => {
        await page.goto("/dashboard");
        await page.getByRole("button", { name: /Open notifications/ }).click();
        await page.getByRole("menuitem", { name: "View all notifications" }).click();
        await expect(page).toHaveURL(/\/notifications/);
        await expect(page.getByRole("heading", { name: "Notifications" })).toBeVisible();
    });
});
