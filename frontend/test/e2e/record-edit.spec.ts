import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

test.describe("record inline edit", () => {
    test("editing a field in the table persists across a reload", async ({ page }) => {
        const contact = runFixture().contacts.edit;
        const newTitle = `Staff Engineer ${Date.now().toString(36)}`;
        const listUrl = `/records/contacts?view=table&q=${encodeURIComponent(contact.name)}`;
        await page.goto(listUrl);

        const editCell = page.getByRole("button", { name: `Edit Title for ${contact.name}` });
        await expect(editCell).toBeVisible();
        await editCell.dblclick();

        const input = page.getByRole("row").filter({ hasText: contact.name }).getByRole("textbox");
        await expect(input).toBeVisible();
        await input.fill(newTitle);
        await input.press("Enter");

        await expect(editCell).toHaveText(newTitle);

        await page.goto(listUrl);
        await expect(page.getByRole("button", { name: `Edit Title for ${contact.name}` })).toHaveText(newTitle);
    });
});
