import { expect, test } from "@playwright/test";

test.describe("quick create", () => {
    test("creating a contact via Quick Create shows it in the records browser", async ({ page }) => {
        const name = `Quick Created ${Date.now().toString(36)}`;
        await page.goto("/dashboard");

        await page.getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("option", { name: "New contact" }).click();

        const dialogName = page.getByLabel("Name", { exact: true });
        await expect(dialogName).toBeVisible();
        await dialogName.fill(name);
        await page.getByRole("button", { name: "Create", exact: true }).click();

        await expect(page.getByText("Contact created").first()).toBeVisible();

        await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(name)}`);
        await expect(page.getByRole("cell", { name }).first()).toBeVisible();
    });
});
