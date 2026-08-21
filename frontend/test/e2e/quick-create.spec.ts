import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

test.describe("quick create", () => {
    test("creating a contact via Quick Create shows it in the records browser", async ({ page }) => {
        const name = `Quick Created ${Date.now().toString(36)}`;
        await page.goto("/dashboard");

        await page
            .getByRole("complementary", { name: "Primary sidebar" })
            .getByRole("button", { name: "New", exact: true })
            .click();
        await page.getByRole("option", { name: "New contact" }).click();

        const dialogName = page.getByLabel("Name", { exact: true });
        await expect(dialogName).toBeVisible();
        await dialogName.fill(name);
        await page.getByRole("button", { name: "Create", exact: true }).click();

        await expect(page.getByText("Contact created").first()).toBeVisible();

        await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(name)}`);
        await expect(page.getByRole("cell", { name }).first()).toBeVisible();
    });

    test("a Quick Create task carries the current member and record into the canonical composer", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.activity;
        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        await page
            .getByRole("complementary", { name: "Primary sidebar" })
            .getByRole("button", { name: "New", exact: true })
            .click();
        await page.getByRole("option", { name: "New task" }).click();

        await expect(page.getByRole("heading", { name: "New task" }).first()).toBeVisible();
        await expect(page.getByLabel("Assignee")).toHaveValue("E2E Harness");
        await expect(page.getByLabel("Contact")).toHaveValue(contact.name);
        await expect(page.getByLabel("Deal")).toHaveValue("");
        await page.getByRole("button", { name: "Cancel", exact: true }).click();
    });
});
