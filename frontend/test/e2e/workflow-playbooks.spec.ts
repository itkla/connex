import { expect, test, type Locator, type Page } from "@playwright/test";

import { runFixture } from "./support/fixtures";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

async function selectOption(page: Page, control: Locator, name: string): Promise<void> {
    await control.click();
    await page.getByRole("option", { name, exact: true }).click();
}

async function closeInspector(page: Page, mobile: boolean): Promise<void> {
    if (mobile) await page.getByRole("dialog").getByRole("button", { name: "Close", exact: true }).click();
}

for (const type of ["person", "company", "deal"] as const) {
    test(`authors and runs a ${type} playbook with frozen inputs and a linked task @mobile`, async ({ page }, testInfo) => {
        test.setTimeout(120_000);
        const fixture = runFixture(testInfo.project.name);
        const mobile = testInfo.project.name === "mobile-chromium";
        const record = type === "person" ? fixture.contacts.peek : type === "company" ? fixture.companies.primary : fixture.deals.primary;
        const route = type === "person" ? "contacts" : type === "company" ? "companies" : "deals";
        const workflowName = `${type} follow-up ${testInfo.testId}`;
        const dueDate = "2026-12-15";
        const consoleErrors: string[] = [];
        page.on("pageerror", (error) => consoleErrors.push(error.message));
        page.on("console", (message) => {
            if (message.type() === "error") consoleErrors.push(message.text());
        });

        await page.goto(`/workflows/new?recordType=${type}&start=manual`);
        await page.getByLabel("Workflow name").fill(workflowName);
        await page.getByLabel("What should this workflow accomplish?").fill("Create a clear next step with a chosen owner and due date.");
        await page.getByRole("button", { name: "Continue to editor" }).click();
        if (!mobile) await page.getByRole("button", { name: "Outline", exact: true }).click();
        const outline = page.getByRole("list", { name: "Workflow steps" });
        await outline.getByRole("button").first().click();
        for (const [label, valueType] of [["Follow-up context", "Text"], ["Task owner", "Member"], ["Due date", "Date"]]) {
            await page.getByRole("button", { name: "Add input", exact: true }).click();
            await page.getByLabel("Input label", { exact: true }).last().fill(label);
            await selectOption(page, page.getByRole("combobox", { name: "Value type", exact: true }).last(), valueType);
        }
        await closeInspector(page, mobile);
        await outline.getByRole("button", { name: "Insert", exact: true }).first().click();
        await page.getByRole("menuitem", { name: "Action", exact: true }).click();
        await outline.getByRole("button", { name: /Create task/ }).click();
        await page.getByRole("textbox", { name: "Title", exact: true }).fill("Follow up: ");
        await selectOption(page, page.getByRole("combobox", { name: "Insert a value into Title", exact: true }), "Input · Follow-up context");
        await selectOption(page, page.getByRole("combobox", { name: "Assignee", exact: true }), "Use an input or record value");
        await selectOption(page, page.getByRole("combobox", { name: "Assignee value", exact: true }), "Input · Task owner");
        await selectOption(page, page.getByRole("combobox", { name: "Task due date", exact: true }), "Input · Due date");
        await closeInspector(page, mobile);

        await page.getByRole("button", { name: "Save draft", exact: true }).click();
        await expect(page).toHaveURL(/\/workflows\/\d+$/);
        await page.getByRole("button", { name: "Validate", exact: true }).click();
        await page.getByRole("button", { name: "Publish", exact: true }).click();
        await page.getByRole("button", { name: "Enable", exact: true }).click();
        await expect(page.getByRole("button", { name: "Disable", exact: true })).toBeVisible();

        await page.goto(`/records/${route}/${record.id}`);
        await page.getByRole("button", { name: "More actions", exact: true }).click();
        await page.getByRole("menuitem", { name: "Run workflow", exact: true }).click();
        const launcher = page.getByRole("dialog");
        await selectOption(page, launcher.getByRole("combobox", { name: "Workflow", exact: true }), workflowName);
        const review = launcher.getByRole("button", { name: "Review the list", exact: true });
        await expect(review).toBeDisabled();
        await launcher.getByLabel("Follow-up context").fill("Initial context");
        await selectOption(page, launcher.getByRole("combobox", { name: /Task owner/ }), "E2E Harness");
        await launcher.getByLabel("Due date").fill(dueDate);
        await review.click();
        await expect(launcher.getByText("Follow up: Initial context", { exact: true })).toBeVisible();
        await launcher.getByRole("button", { name: "Change selection", exact: true }).click();
        await launcher.getByLabel("Follow-up context").fill(workflowName);
        await expect(launcher.getByRole("button", { name: "Start the run", exact: true })).toHaveCount(0);
        await review.click();
        await expect(launcher.getByText(`Follow up: ${workflowName}`, { exact: true })).toBeVisible();
        await launcher.getByRole("button", { name: "Start the run", exact: true }).click();
        await expect(launcher.getByText("Run started", { exact: true })).toBeVisible();

        let createdTask: Record<string, unknown> | undefined;
        await expect.poll(async () => {
            const response = await page.request.get("/api/tasks");
            expect(response.ok()).toBe(true);
            const tasks: unknown = await response.json();
            if (!Array.isArray(tasks)) throw new Error("Task collection must be an array");
            createdTask = tasks.find((task): task is Record<string, unknown> => isRecord(task) && task.description === `Follow up: ${workflowName}`);
            return createdTask !== undefined;
        }, { timeout: 30_000 }).toBe(true);
        expect(createdTask?.[type === "person" ? "personId" : type === "company" ? "companyId" : "dealId"]).toBe(record.id);
        expect(createdTask?.dueDate).toBe(dueDate);
        expect(typeof createdTask?.assignedToId).toBe("number");
        await launcher.getByRole("button", { name: "Refresh", exact: true }).click();
        await launcher.getByRole("link", { name: "View run", exact: true }).click();
        await expect(page.getByRole("link", { name: "View created task", exact: true })).toHaveAttribute("href", `/activity/tasks?task=${String(createdTask?.id)}`);
        await page.getByRole("link", { name: "View created task", exact: true }).click();
        await expect(page.getByRole("dialog")).toBeVisible();
        if (type === "company") await expect(page.getByRole("link", { name: "View linked company", exact: true })).toHaveAttribute("href", `/records/companies/${record.id}`);
        expect(consoleErrors).toEqual([]);
    });
}
