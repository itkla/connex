import { expect, test } from "@playwright/test";
import { message } from "./support/messages";
import { useLocale } from "./support/locale";

test.describe("japanese locale", () => {
    test.describe("logged out", () => {
        test.use({ storageState: { cookies: [], origins: [] } });

        test("the login page renders Japanese copy when the locale cookie is set @mobile", async ({ page }) => {
            await useLocale(page, "ja");
            await page.goto("/auth/login");

            await expect(page.getByRole("heading", { name: message("ja", "auth", "AuthLogin.title") }))
                .toBeVisible();
            await expect(page.getByLabel(message("ja", "auth", "AuthForm.labelLoginIdentifier"), { exact: true }))
                .toBeVisible();
            await expect(page.getByRole("button", { name: message("ja", "auth", "AuthLogin.submitLabel"), exact: true }))
                .toBeVisible();
        });

        test("without the cookie the same page stays in English", async ({ page }) => {
            await page.goto("/auth/login");

            await expect(page.getByRole("heading", { name: message("en", "auth", "AuthLogin.title") }))
                .toBeVisible();
        });
    });

    test("the signed-in app shell renders Japanese", async ({ page }) => {
        await useLocale(page, "ja");
        await page.goto("/dashboard");

        const sidebar = page.getByRole("complementary", {
            name: message("ja", "common", "CommonSidebar.ariaPrimarySidebar"),
            exact: true,
        });
        await expect(sidebar).toBeVisible();
        await expect(sidebar.getByRole("link", { name: message("ja", "common", "CommonSidebar.navDashboard") }))
            .toBeVisible();
        await expect(sidebar.getByRole("link", { name: message("ja", "common", "CommonSidebar.navContacts") }))
            .toBeVisible();
    });
});
