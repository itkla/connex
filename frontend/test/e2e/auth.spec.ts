import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

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

    test("logging in with an existing account lands on the dashboard", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        await page.goto("/auth/login");
        await page.getByLabel("Username or email").fill(fixture.username);
        await page.getByLabel("Password", { exact: true }).fill(fixture.password);
        await page.getByRole("button", { name: "Sign in", exact: true }).click();
        await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });
    });

    test("a wrong password shows the invalid-credentials error", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        await page.goto("/auth/login");
        await page.getByLabel("Username or email").fill(fixture.username);
        await page.getByLabel("Password", { exact: true }).fill("Wrong!Password1");
        await page.getByRole("button", { name: "Sign in", exact: true }).click();
        await expect(page.locator("form").getByRole("alert")).toContainText("Invalid username or password");
    });
});
