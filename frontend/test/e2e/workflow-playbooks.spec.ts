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

for (const type of ["task", "document"] as const) {
    test(`preserves new ${type} workflows and their existing events @mobile`, async ({ page }, testInfo) => {
        const mobile = testInfo.project.name === "mobile-chromium";
        await page.goto(`/workflows/new?recordType=${type}`);
        await page.getByLabel("Workflow name").fill(`${type} automation ${testInfo.testId}`);
        await expect(page.getByRole("button", { name: "Manually from a record", exact: true })).toHaveCount(0);
        await page.getByRole("button", { name: "Continue to editor" }).click();
        if (!mobile) await page.getByRole("button", { name: "Outline", exact: true }).click();
        await page.getByRole("list", { name: "Workflow steps" }).getByRole("button").first().click();
        await page.getByRole("button", { name: type === "task" ? "completed" : "approved", exact: true }).click();
        await expect(page.getByRole("button", { name: "Add input", exact: true })).toHaveCount(0);
        await closeInspector(page, mobile);
        const created = page.waitForResponse((response) => response.request().method() === "POST"
            && new URL(response.url()).pathname === "/api/workflows");
        await page.getByRole("button", { name: "Save draft", exact: true }).click();
        const response = await created;
        expect(response.status(), await response.text()).toBe(201);
        const body: unknown = await response.json();
        if (!isRecord(body) || !isRecord(body.definition)) throw new Error("Expected the created workflow definition");
        expect(body.recordType).toBe(type);
        expect(body.definition.schemaVersion).toBe(1);
        expect(body.definition.inputs).toBeUndefined();
        await expect(page).toHaveURL(/\/workflows\/\d+$/);
    });
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
        await outline.getByRole("button", { name: /^create a task/i }).click();
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
            const filter = type === "person" ? "personId" : type === "company" ? "companyId" : "dealId";
            const response = await page.request.get(`/api/tasks?${filter}=${record.id}`);
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

test("resumes a process only after its created task is completed @mobile", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const record = runFixture(testInfo.project.name).contacts.peek;
    const mobile = testInfo.project.name === "mobile-chromium";
    const workflowName = `Task completion ${testInfo.testId}`;
    const firstTitle = `Confirm handoff ${testInfo.testId}`;
    const nextTitle = `Continue handoff ${testInfo.testId}`;
    await page.goto("/workflows/new?recordType=person&start=manual");
    await page.getByLabel("Workflow name").fill(workflowName);
    await page.getByRole("button", { name: "Continue to editor" }).click();
    if (!mobile) await page.getByRole("button", { name: "Outline", exact: true }).click();
    const outline = page.getByRole("list", { name: "Workflow steps" });
    await outline.getByRole("button", { name: "Insert", exact: true }).first().click();
    await page.getByRole("menuitem", { name: "Action", exact: true }).click();
    await outline.getByRole("button", { name: /^create a task/i }).first().click();
    await page.getByRole("textbox", { name: "Title", exact: true }).fill(firstTitle);
    await selectOption(page, page.getByRole("combobox", { name: "Assignee", exact: true }), "E2E Harness");
    await closeInspector(page, mobile);
    const firstRow = outline.getByRole("listitem").filter({ hasText: firstTitle });
    await firstRow.getByRole("button", { name: "Insert", exact: true }).click();
    await page.getByRole("menuitem", { name: "Wait for task", exact: true }).click();
    const waitRow = outline.getByRole("listitem").filter({ has: page.getByRole("button", { name: /^Wait for task/ }) });
    await expect(waitRow.getByText("Completed", { exact: true })).toBeVisible();
    await expect(waitRow.getByText("Timed out", { exact: true })).toBeVisible();
    await waitRow.getByRole("button", { name: "Insert", exact: true }).first().click();
    await page.getByRole("menuitem", { name: "Action", exact: true }).click();
    await outline.getByRole("button", { name: /^create a task/i }).last().click();
    await page.getByRole("textbox", { name: "Title", exact: true }).fill(nextTitle);
    await selectOption(page, page.getByRole("combobox", { name: "Assignee", exact: true }), "E2E Harness");
    await closeInspector(page, mobile);
    await page.getByRole("button", { name: "Save draft", exact: true }).click();
    await expect(page).toHaveURL(/\/workflows\/\d+$/);
    await page.getByRole("button", { name: "Validate", exact: true }).click();
    await page.getByRole("button", { name: "Publish", exact: true }).click();
    await page.getByRole("button", { name: "Enable", exact: true }).click();
    await expect(page.getByRole("button", { name: "Disable", exact: true })).toBeVisible();
    await page.goto(`/records/contacts/${record.id}`);
    await page.getByRole("button", { name: "More actions", exact: true }).click();
    await page.getByRole("menuitem", { name: "Run workflow", exact: true }).click();
    const launcher = page.getByRole("dialog");
    await selectOption(page, launcher.getByRole("combobox", { name: "Workflow", exact: true }), workflowName);
    await launcher.getByRole("button", { name: "Review the list", exact: true }).click();
    await launcher.getByRole("button", { name: "Start the run", exact: true }).click();
    await expect(launcher.getByText("Run started", { exact: true })).toBeVisible();
    let firstTaskId: number | undefined;
    const tasks = async () => {
        const response = await page.request.get(`/api/tasks?personId=${record.id}`);
        expect(response.ok()).toBe(true);
        const value: unknown = await response.json();
        if (!Array.isArray(value)) throw new Error("Task collection must be an array");
        return value.filter(isRecord);
    };
    await expect.poll(async () => {
        const task = (await tasks()).find((task) => task.description === firstTitle);
        firstTaskId = typeof task?.id === "number" ? task.id : undefined;
        return firstTaskId !== undefined;
    }, { timeout: 30_000 }).toBe(true);
    expect((await tasks()).filter((task) => task.description === nextTitle)).toHaveLength(0);
    await launcher.getByRole("button", { name: "Refresh", exact: true }).click();
    await launcher.getByRole("link", { name: "View run", exact: true }).click();
    const runUrl = page.url();
    await expect(page.getByText("Waiting for the task to be completed", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "View the task", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`task=${firstTaskId}`));
    await page.getByRole("dialog").getByRole("checkbox", { name: "Completed", exact: true }).check();
    await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect.poll(async () => (await tasks()).filter((task) => task.description === nextTitle).length, { timeout: 30_000 }).toBe(1);
    await page.goto(runUrl);
    await expect(page.getByText("Task completion observed", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel run", exact: true })).toHaveCount(0);
});
