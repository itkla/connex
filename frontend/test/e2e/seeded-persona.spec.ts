import { expect, test } from "@playwright/test";
import { message } from "./support/messages";
import { seedFixture, seedFixtureAvailable } from "./support/seed";

test.use({ storageState: { cookies: [], origins: [] } });

test.describe("volume seeder", () => {
    test("the Japanese persona from the deterministic seed can sign in", async ({ page }) => {
        test.skip(!seedFixtureAvailable(), "the deterministic volume seeder has not run against this stack");

        const fixture = seedFixture();
        const workspace = fixture.workspaces[0];
        const persona = workspace.japaneseUser;
        expect(persona.locale).toBe("ja");

        await page.goto("/auth/login");
        await page.getByLabel(message("en", "auth", "AuthForm.labelLoginIdentifier"), { exact: true })
            .fill(persona.username);
        await page.getByLabel(message("en", "auth", "AuthForm.labelPassword"), { exact: true })
            .fill(fixture.password);
        await page.getByRole("button", { name: message("en", "auth", "AuthLogin.submitLabel"), exact: true })
            .click();

        await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });
        await expect(page.getByRole("link", { name: message("ja", "common", "CommonSidebar.navContacts") }).first())
            .toBeVisible();
    });
});
