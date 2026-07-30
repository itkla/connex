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
        await subjectField.fill(subject);
        await page.getByRole("button", { name: "Log", exact: true }).click();

        await expect(page.getByText("Activity logged").first()).toBeVisible();
        await expect(page.getByText(subject).first()).toBeVisible({ timeout: 15_000 });
    });
});
