import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";

test.describe("activity composer", () => {
    test("logging an activity on a contact shows it in the timeline", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.activity;
        const subject = `Kickoff sync ${Date.now().toString(36)}`;
        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        await page.getByRole("button", { name: "Log activity" }).first().click();
        const subjectField = page.getByLabel("Subject");
        await expect(subjectField).toBeVisible();
        await expect(page.getByLabel("Contact")).toHaveValue(contact.name);
        await subjectField.fill(subject);
        await page.getByRole("button", { name: "Create", exact: true }).click();

        await expect(page.getByText("Activity logged").first()).toBeVisible();
        await expect(page.getByText(subject).first()).toBeVisible({ timeout: 15_000 });
    });

    test("a contact task opens the canonical composer with the contact pre-linked", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.activity;
        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        await page.getByRole("main").getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("menuitem", { name: "Add task" }).click();

        await expect(page.getByRole("heading", { name: "New task" }).first()).toBeVisible();
        await expect(page.getByLabel("Contact")).toHaveValue(contact.name);
        await page.getByRole("button", { name: "Cancel", exact: true }).click();
    });

    test("deal task and activity composers pre-link the deal", async ({ page }, testInfo) => {
        const deal = runFixture(testInfo.project.name).deals.primary;
        await page.goto(`/records/deals/${deal.id}`);
        await expect(page.getByRole("heading", { name: deal.name }).first()).toBeVisible();

        await page.getByRole("main").getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("menuitem", { name: "Add task" }).click();
        await expect(page.getByRole("heading", { name: "New task" }).first()).toBeVisible();
        await expect(page.getByRole("combobox", { name: "Deal" })).toHaveValue(deal.name);
        await page.getByRole("button", { name: "Cancel", exact: true }).click();

        await page.getByRole("main").getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("menuitem", { name: "Add activity" }).click();
        await expect(page.getByRole("heading", { name: "Log activity" }).first()).toBeVisible();
        await expect(page.getByRole("combobox", { name: "Deal" })).toHaveValue(deal.name);
        await page.getByRole("button", { name: "Cancel", exact: true }).click();
    });
});
