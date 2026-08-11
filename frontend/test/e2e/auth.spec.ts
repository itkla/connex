import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";
import { useLocale } from "./support/locale";

test.use({ storageState: { cookies: [], origins: [] } });

test.describe("auth", () => {
    test("visiting a protected route while logged out redirects to login", async ({ page }) => {
        await page.goto("/dashboard");
        await expect(page).toHaveURL(/\/auth\/login/);
        await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
    });

    test("registering through the UI lands on the dashboard", async ({ page }) => {
        const runId = `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
        await page.goto("/auth/register");
        await page.getByLabel("Username", { exact: true }).fill(`e2e_ui_${runId}`);
        await page.getByLabel("Email", { exact: true }).fill(`e2e_ui_${runId}@example.com`);
        await page.getByLabel("Name", { exact: true }).fill("UI Register");
        await page.getByLabel("Password", { exact: true }).fill(`E2eHarness!${runId}A1`);
        await page.getByRole("button", { name: "Create account" }).click();
        await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });
    });

    for (const { locale, language } of [
        { locale: "en", language: "English" },
        { locale: "ja", language: "Japanese" },
    ] as const) {
        test(`${language} login reports invalid credentials and signs in through the form`, async ({ page }, testInfo) => {
            const fixture = runFixture(testInfo.project.name);
            await useLocale(page, locale);
            await page.goto("/auth/login");

            const identifier = page.getByLabel(
                message(locale, "auth", "AuthForm.labelLoginIdentifier"),
                { exact: true },
            );
            const password = page.getByLabel(message(locale, "auth", "AuthForm.labelPassword"), { exact: true });
            const submit = page.getByRole(
                "button",
                { name: message(locale, "auth", "AuthLogin.submitLabel"), exact: true },
            );

            await expect(identifier).toBeEnabled();
            await identifier.fill(fixture.username);
            await password.fill("Wrong!Password1");

            const rejectedLogin = page.waitForResponse((response) => (
                response.url().endsWith("/api/auth/login") && response.request().method() === "POST"
            ));
            await submit.click();
            expect((await rejectedLogin).status()).toBe(401);
            await expect(page.locator("form").getByRole("alert")).toHaveText(
                message(locale, "auth", "AuthLogin.invalidCredentials"),
            );

            await password.fill(fixture.password);
            const acceptedLogin = page.waitForResponse((response) => (
                response.url().endsWith("/api/auth/login") && response.request().method() === "POST"
            ));
            await submit.click();
            expect((await acceptedLogin).status()).toBe(200);
            await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });

            const authenticatedUser = await page.request.get("/api/auth/me");
            expect(authenticatedUser.status()).toBe(200);
            await expect(page.getByRole("link", {
                name: message(locale, "common", "CommonSidebar.navDashboard"),
                exact: true,
            }).first()).toBeVisible();
        });
    }
});
