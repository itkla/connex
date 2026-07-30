import { expect, test } from "@playwright/test";
import { message } from "./support/messages";

test.describe("mobile shell", () => {
    test("the phone viewport swaps the sidebar for the bottom bar @mobile-only", async ({ page }) => {
        await page.goto("/dashboard");

        const bottomBar = page.getByRole("navigation", {
            name: message("en", "common", "MobileNav.barLabel"),
            exact: true,
        });
        await expect(bottomBar).toBeVisible();
        await expect(bottomBar.getByRole("link", { name: message("en", "common", "CommonSidebar.navDashboard") }))
            .toBeVisible();
        await expect(page.getByRole("button", { name: message("en", "common", "CommonContentShell.showSidebar") }))
            .toBeVisible();
    });
});
