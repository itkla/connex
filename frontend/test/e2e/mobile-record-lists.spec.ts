import { expect, test, type Page } from "@playwright/test";

import { csrfBootstrap, seeder, type RunFixture } from "./support/api";
import { runFixture } from "./support/fixtures";

test.describe("mobile record lists", () => {
    test("record pages force rows and restore the stored desktop view @mobile-only", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.search;
        const contactQuery = encodeURIComponent(contact.name);

        await page.setViewportSize({ width: 1024, height: 800 });
        await page.goto(`/records/contacts?q=${contactQuery}`);
        await page.getByRole("group", { name: "Display mode" })
            .getByRole("button", { name: "Grid view" })
            .click();

        const contactCard = page.locator("div.group").filter({ hasText: contact.name }).first();
        await expect(contactCard).toBeVisible();
        await expect.poll(() => storedView(page, "contacts:view")).toBe("grid");

        await page.setViewportSize({ width: 412, height: 915 });
        await expect(mobileRow(page, contact.name)).toBeVisible();
        await expect(contactCard).toBeHidden();
        await expect.poll(() => storedView(page, "contacts:view")).toBe("grid");

        await page.goto(`/records/contacts?q=${contactQuery}`);
        await expect(mobileRow(page, contact.name)).toBeVisible();
        await page.setViewportSize({ width: 1024, height: 800 });
        await expect(page.locator("div.group").filter({ hasText: contact.name }).first()).toBeVisible();

        await page.setViewportSize({ width: 412, height: 915 });
        await page.goto(`/records/companies?q=${encodeURIComponent(fixture.companyName)}`);
        await expect(mobileRow(page, fixture.companyName)).toBeVisible();

        await page.goto(`/records/deals?q=${encodeURIComponent("E2E Deal 1")}`);
        await expect(mobileRow(page, "E2E Deal 1")).toBeVisible();
    });

    test("phone filter sheets sort records and filter tasks @mobile-only", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.search;
        const taskDescription = `Mobile task ${fixture.username} retry ${testInfo.retry}`;
        await createTask(page, fixture, taskDescription);

        await page.goto(`/records/contacts?q=${encodeURIComponent(contact.name)}`);
        await page.getByRole("button", { name: "Filter and sort records" }).click();
        const recordsSheet = page.getByRole("dialog", { name: "Filter & sort" });
        await recordsSheet.getByRole("button", { name: "Name", exact: true }).click();
        await recordsSheet.getByRole("button", { name: fixture.companyName, exact: true }).click();
        await recordsSheet.getByRole("button", { name: "Done", exact: true }).click();

        await expect.poll(() => new URL(page.url()).searchParams.get("sort")).toBe("name");
        await expect.poll(() => new URL(page.url()).searchParams.get("company")).toBe(fixture.companyName);
        await expect(mobileRow(page, contact.name)).toBeVisible();

        await page.setViewportSize({ width: 1024, height: 800 });
        await page.goto("/activity/tasks");
        const taskDisplayMode = page.getByRole("group", { name: "Display mode" });
        await taskDisplayMode.getByRole("button", { name: "Board view" }).click();
        await expect(taskDisplayMode.getByRole("button", { name: "Board view" })).toHaveAttribute("aria-pressed", "true");

        await page.setViewportSize({ width: 412, height: 915 });
        const taskRow = mobileRow(page, taskDescription);
        await expect(taskRow).toBeVisible();
        await expect(taskRow.getByRole("button", { name: `Actions for ${taskDescription}` })).toBeVisible();
        await page.getByRole("button", { name: "Filter tasks" }).click();
        const tasksSheet = page.getByRole("dialog", { name: "Filters" });
        await tasksSheet.getByRole("button").filter({ hasText: contact.name }).click();
        await tasksSheet.getByRole("button", { name: "Done", exact: true }).click();
        await expect(taskRow).toBeVisible();
    });
});

function mobileRow(page: Page, text: string) {
    return page.getByRole("listitem").filter({ hasText: text }).first();
}

async function storedView(page: Page, suffix: string): Promise<string | null> {
    return page.evaluate((keySuffix) => {
        for (let index = 0; index < window.localStorage.length; index++) {
            const key = window.localStorage.key(index);
            if (key?.endsWith(keySuffix)) return window.localStorage.getItem(key);
        }
        return null;
    }, suffix);
}

async function createTask(
    page: Page,
    fixture: RunFixture,
    description: string,
): Promise<void> {
    const response = await page.request.get("/api/auth/me");
    expect(response.status()).toBe(200);
    const currentUser: unknown = await response.json();
    if (
        typeof currentUser !== "object"
        || currentUser === null
        || !("id" in currentUser)
        || typeof currentUser.id !== "number"
    ) {
        throw new Error("Invalid current-user response");
    }
    const csrf = await csrfBootstrap(page.request);
    await seeder(page.request, fixture.workspaceId, csrf).post("/api/tasks", {
        description,
        completed: false,
        assignedToId: currentUser.id,
        personId: fixture.contacts.search.id,
    });
}
