import { expect, test } from "@playwright/test";
import { useLocale } from "./support/locale";
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

    test("the compact breadcrumb is localized and does not widen the phone shell @mobile-only", async ({ page }) => {
        await useLocale(page, "ja");
        await page.goto("/settings/general");

        const breadcrumb = page.getByRole("navigation", {
            name: message("ja", "common", "CommonBreadcrumb.ariaLabel"),
        });
        await expect(breadcrumb).toBeVisible();
        await expect(breadcrumb.getByRole("link", {
            name: message("ja", "common", "CommonBreadcrumb.settings"),
            exact: true,
        })).toBeVisible();
        await expect(breadcrumb).toContainText(message("ja", "common", "CommonBreadcrumb.general"));
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    });
});
