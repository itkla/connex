import { expect, test, type Locator, type Page } from "@playwright/test";

import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";

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

test("keeps deleted input bindings unresolved and fits sixteen preview inputs", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width: 1024, height: 768 });
    await page.goto("/workflows/new?recordType=person&start=manual");
    await page.getByLabel("Workflow name").fill(`Input identity ${testInfo.testId}`);
    await page.getByRole("button", { name: "Continue to editor" }).click();
    await page.getByRole("button", { name: "Outline", exact: true }).click();
    const outline = page.getByRole("list", { name: "Workflow steps" });
    await outline.getByRole("button").first().click();
    await page.getByRole("button", { name: "Add input", exact: true }).click();
    await page.getByLabel("Input label", { exact: true }).fill("Original context");
    const originalId = await page.getByLabel("Input label", { exact: true }).getAttribute("id");
    if (!originalId?.startsWith("input-label-")) throw new Error("Expected the original input identity");
    const originalKey = originalId.slice("input-label-".length);
    await outline.getByRole("button", { name: "Insert", exact: true }).first().click();
    await page.getByRole("menuitem", { name: "Action", exact: true }).click();
    await outline.getByRole("button", { name: /^create a task/i }).click();
    await selectOption(page, page.getByRole("combobox", { name: "Insert a value into Title", exact: true }), "Input · Original context");
    await outline.getByRole("button").first().click();
    await page.getByRole("button", { name: "Remove Original context", exact: true }).click();
    for (let index = 1; index <= 16; index += 1) {
        await page.getByRole("button", { name: "Add input", exact: true }).click();
        await page.getByLabel("Input label", { exact: true }).last().fill(`Context ${index}`);
    }
    await expect(page.getByRole("button", { name: "Add input", exact: true })).toBeDisabled();
    const created = page.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname === "/api/workflows");
    await page.getByRole("button", { name: "Save draft", exact: true }).click();
    const response = await created;
    expect(response.status(), await response.text()).toBe(201);
    const workflow: unknown = await response.json();
    if (!isRecord(workflow) || !isRecord(workflow.definition) || !Array.isArray(workflow.definition.inputs)
        || !Array.isArray(workflow.definition.nodes)) throw new Error("Expected the saved input bindings");
    expect(workflow.definition.inputs.every((input) => isRecord(input) && input.key !== originalKey)).toBe(true);
    const action = workflow.definition.nodes.find((node): node is Record<string, unknown> => isRecord(node) && node.type === "ACTION");
    expect(action?.config).toMatchObject({ titleTemplate: { parts: expect.arrayContaining([{ ref: { source: "launch_input", key: originalKey } }]) } });
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    const dialog = page.getByRole("dialog");
    for (let index = 1; index <= 16; index += 1) {
        const input = dialog.getByLabel(`Context ${index}`, { exact: true });
        await input.scrollIntoViewIfNeeded();
        await expect(input).toBeInViewport();
    }
    await expect(dialog.getByRole("button", { name: "Preview path", exact: true })).toBeInViewport();
    await expect(dialog.getByRole("heading")).toBeInViewport();
});

test("filters date-start time zones and selects the match with the keyboard @mobile", async ({ page }, testInfo) => {
    const mobile = testInfo.project.name === "mobile-chromium";
    await page.goto("/workflows/new?recordType=deal&start=date");
    await page.getByLabel("Workflow name").fill(`Date zone ${testInfo.testId}`);
    await page.getByRole("button", { name: "Continue to editor" }).click();
    if (!mobile) await page.getByRole("button", { name: "Outline", exact: true }).click();
    await page.getByRole("list", { name: "Workflow steps" }).getByRole("button").first().click();
    const timezone = page.getByRole("combobox", { name: "Time zone", exact: true });
    await timezone.fill("Tokyo");
    await expect(page.getByRole("option", { name: "Asia/Tokyo", exact: true })).toBeVisible();
    await expect(page.getByRole("option", { name: "UTC", exact: true })).toHaveCount(0);
    await expect(page.getByRole("option", { name: "America/New_York", exact: true })).toHaveCount(0);
    await timezone.press("ArrowDown");
    await timezone.press("Enter");
    await expect(timezone).toHaveValue("Asia/Tokyo");
});

test("authors Japanese launch inputs in dark mode without horizontal overflow @mobile", async ({ page }, testInfo) => {
    const mobile = testInfo.project.name === "mobile-chromium";
    await useLocale(page, "ja");
    await page.addInitScript(() => window.localStorage.setItem("theme", "dark"));
    await page.goto("/workflows/new?recordType=company&start=manual");
    await expect(page.locator("html")).toHaveClass(/dark/);
    await page.getByLabel("ワークフロー名", { exact: true }).fill(`会社レビュー ${testInfo.testId}`);
    await expect(page.getByRole("button", { name: "レコードから手動で実行", exact: true })).toBeVisible();
    await page.getByRole("button", { name: "エディターに進む", exact: true }).click();
    if (!mobile) await page.getByRole("button", { name: "アウトライン", exact: true }).click();
    await page.getByRole("list", { name: "ワークフローのステップ" }).getByRole("button").first().click();
    await page.getByRole("button", { name: "入力項目を追加", exact: true }).click();
    await page.getByLabel("入力項目のラベル", { exact: true }).fill("今回確認する内容");
    await page.getByLabel("既定値（任意）", { exact: true }).fill("次回の打ち合わせと担当者を確認");
    await expect(page.getByRole("heading", { name: "実行時の入力", exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("workflow-ja-dark.png"), fullPage: true });
    await closeInspector(page, mobile);
    const created = page.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname === "/api/workflows");
    await page.getByRole("button", { name: "下書きを保存", exact: true }).click();
    const response = await created;
    expect(response.status(), await response.text()).toBe(201);
    const workflow: unknown = await response.json();
    if (!isRecord(workflow) || !isRecord(workflow.definition)) throw new Error("Expected the Japanese workflow draft");
    expect(workflow.definition.inputs).toEqual([expect.objectContaining({ label: "今回確認する内容", defaultValue: "次回の打ち合わせと担当者を確認" })]);
});

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
        if (type === "deal") {
            const taskRow = outline.getByRole("listitem").filter({ has: page.getByRole("button", { name: /^create a task/i }) });
            await taskRow.getByRole("button", { name: "Insert", exact: true }).click();
            await page.getByRole("menuitem", { name: "Action", exact: true }).click();
            await outline.getByRole("button", { name: /^create a task/i }).last().click();
            await selectOption(page, page.getByRole("combobox", { name: "Action", exact: true }), "update expected close date");
            await page.getByRole("switch", { name: "Use an input or record value", exact: true }).click();
            await selectOption(page, page.getByRole("combobox", { name: "New expected close date", exact: true }), "Input · Due date");
            await closeInspector(page, mobile);
        }

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
        if (type === "deal") {
            await expect.poll(async () => {
                const response = await page.request.get(`/api/deals/${record.id}`);
                expect(response.ok()).toBe(true);
                const deal: unknown = await response.json();
                return isRecord(deal) ? deal.expectedCloseDate : null;
            }, { timeout: 30_000 }).toBe(dueDate);
        }
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

test("configures a renewal recipe against a deal date and creates it turned off @mobile", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const name = `Renewal preparation ${testInfo.testId}`;
    await page.goto("/workflows/recipes/deal-renewal-preparation");
    await expect(page.getByRole("heading", { name: "Prepare for a renewal", exact: true })).toBeVisible();
    await page.getByLabel("Workflow name", { exact: true }).fill(name);
    await selectOption(page, page.getByRole("combobox", { name: "Runs as", exact: true }), "E2E Harness");
    await selectOption(page, page.getByRole("combobox", { name: "Task assignee", exact: true }), "E2E Harness");
    await page.getByLabel("Task title", { exact: true }).fill("Prepare the renewal discussion");
    await page.getByLabel("Days from expected close date", { exact: true }).fill("-30");
    await page.getByLabel("Start time", { exact: true }).fill("09:00");
    await page.getByLabel("Time zone", { exact: true }).fill("Asia/Tokyo");
    await page.getByLabel("Due in days", { exact: true }).fill("5");
    await page.getByLabel("Wait for completion, in days", { exact: true }).fill("7");
    await expect(page.getByText(/This recipe uses the deal’s expected close date/)).toBeVisible();
    const previewed = page.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname === "/api/workflow-recipes/deal-renewal-preparation/preview");
    await page.getByRole("button", { name: "Preview", exact: true }).click();
    const previewResponse = await previewed;
    expect(previewResponse.status(), await previewResponse.text()).toBe(200);
    const preview: unknown = await previewResponse.json();
    if (!isRecord(preview) || !isRecord(preview.definition) || !Array.isArray(preview.definition.nodes)) throw new Error("Expected the recipe preview definition");
    expect(preview.writesCreated).toBe(false);
    expect(preview.definition.schemaVersion).toBe(2);
    const trigger = preview.definition.nodes.find((node): node is Record<string, unknown> => isRecord(node) && node.type === "TRIGGER");
    expect(trigger?.config).toMatchObject({ type: "date", dateField: "expectedCloseDate", offsetDays: -30, localTime: "09:00", timezone: "Asia/Tokyo" });
    expect(preview.definition.nodes.some((node) => isRecord(node) && node.type === "WAIT")).toBe(true);
    await expect(page.getByText("Confirmed: nothing was created or changed.", { exact: true })).toBeVisible();
    const installed = page.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname === "/api/workflow-recipes/deal-renewal-preparation/install");
    await page.getByRole("button", { name: "Create it, turned off", exact: true }).click();
    const installResponse = await installed;
    expect(installResponse.ok(), await installResponse.text()).toBe(true);
    const result: unknown = await installResponse.json();
    if (!isRecord(result) || !isRecord(result.workflow)) throw new Error("Expected the installed workflow");
    expect(result.workflow.enabled).toBe(false);
    await expect(page).toHaveURL(/\/workflows\/\d+$/);
    await expect(page.getByRole("button", { name: "Enable", exact: true })).toBeVisible();
});
