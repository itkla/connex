import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

test.describe("global search", () => {
    test("the toolbar search finds a seeded contact and opens its record", async ({ page }) => {
        const contact = runFixture().contacts.search;
        await page.goto("/dashboard");

        const searchInput = page.getByRole("combobox", { name: /search/i })
            .or(page.getByPlaceholder("Search for anything"));
        await expect(searchInput.first()).toBeVisible();
        await searchInput.first().fill(contact.name);

        const result = page.getByRole("option", { name: new RegExp(contact.name) });
        await expect(result.first()).toBeVisible();
        await result.first().click();

        await expect(page).toHaveURL(new RegExp(`/records/contacts/${contact.id}`));
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
    });
});
