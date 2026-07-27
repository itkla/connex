import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

test.describe("records browse and peek", () => {
    test("row opens a peek, peek opens the detail, and back restores the list context", async ({ page }) => {
        const contact = runFixture().contacts.peek;
        const listUrl = `/records/contacts?view=table&q=${encodeURIComponent(contact.name)}`;
        await page.goto(listUrl);

        const row = page.getByRole("row").filter({ hasText: contact.name });
        await expect(row).toBeVisible();
        await row.getByRole("cell", { name: contact.name, exact: true }).click();

        const peek = page.locator("[data-record-peek]");
        await expect(peek).toBeVisible();
        await expect(peek.getByText(contact.name).first()).toBeVisible();
        await expect(page).toHaveURL(new RegExp(`peek=person%3A${contact.id}`));

        await peek.getByRole("button", { name: "Open record" }).click();
        await expect(page).toHaveURL(new RegExp(`/records/contacts/${contact.id}`));
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        await page.goBack();
        const qPattern = encodeURIComponent(contact.name).replace(/%20/g, "(?:%20|\\+)");
        await expect(page).toHaveURL(new RegExp(`q=${qPattern}`));
        await expect(page.getByRole("row").filter({ hasText: contact.name })).toBeVisible();
    });
});
