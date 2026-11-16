import { randomUUID } from "node:crypto";
import { expect, test, type Locator, type Page } from "@playwright/test";
import { csrfBootstrap, seeder } from "./support/api";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";

test.beforeEach(async ({ page }) => { await useLocale(page, "en"); });

async function expectNormalStylesUnchanged(control: Locator) {
    await expect(control).toBeVisible();
    const readStyles = () => control.evaluate((element) => {
        const style = getComputedStyle(element);
        return Object.fromEntries([
            "width", "height", "font-size", "line-height", "color", "background-color",
            "padding-top", "padding-right", "padding-bottom", "padding-left", "border-radius",
            "border-top-width", "border-top-style", "border-top-color", "box-shadow",
        ].map((property) => [property, style.getPropertyValue(property)]));
    });
    const before = await readStyles();
    const original = await control.getAttribute("class");
    await control.evaluate((element) => {
        element.setAttribute("class", (element.getAttribute("class") ?? "").split(" ")
            .filter((name) => !name.startsWith("forced-colors:"))
            .map((name) => name === "outline-hidden" ? "outline-none" : name).join(" "));
    });
    try {
        expect(await readStyles()).toEqual(before);
    } finally {
        await control.evaluate((element, className) => { element.setAttribute("class", className ?? ""); }, original);
    }
}

async function expectVisibleEdgesBelongTo(control: Locator) {
    await control.scrollIntoViewIfNeeded();
    const hits = await control.evaluate((element) => {
        const box = element.getBoundingClientRect();
        const centerX = box.left + box.width / 2;
        const centerY = box.top + box.height / 2;
        return [
            [box.left + 1, centerY], [box.right - 1, centerY],
            [centerX, box.top + 1], [centerX, box.bottom - 1],
        ].map(([x, y]) => document.elementFromPoint(x, y)?.closest("button") === element);
    });
    expect(hits).toEqual([true, true, true, true]);
}

async function createTask(page: Page, workspaceId: number, personId: number, description: string) {
    const response = await page.request.get("/api/auth/me");
    expect(response.ok()).toBe(true);
    const user: unknown = await response.json();
    if (typeof user !== "object" || user === null || !("id" in user) || typeof user.id !== "number") {
        throw new Error("Missing current member id");
    }
    const created = await seeder(page.request, workspaceId, await csrfBootstrap(page.request)).post("/api/tasks", {
        description, completed: false, assignedToId: user.id, personId,
    });
    if (typeof created.id !== "number") throw new Error("Missing task id");
    return created.id;
}

async function deleteTask(page: Page, workspaceId: number, taskId: number) {
    const csrf = await csrfBootstrap(page.request);
    const response = await page.request.delete(`/api/tasks/${taskId}`, {
        headers: { "X-Workspace-Id": String(workspaceId), [csrf.headerName]: csrf.token },
    });
    expect(response.ok()).toBe(true);
}

async function expectHitArea(control: Locator, visualSize: number) {
    await expect(control).toHaveCSS("width", `${visualSize}px`);
    const target = await control.evaluate((element) => {
        const hit = getComputedStyle(element, "::after");
        return { width: parseFloat(hit.width), height: parseFloat(hit.height) };
    });
    expect(target.width).toBeGreaterThanOrEqual(44);
    expect(target.height).toBeGreaterThanOrEqual(44);
}

async function expectForcedFocus(control: Locator) {
    await control.press("Tab");
    await control.focus();
    await expect(control).toBeFocused();
    await expect(control).toHaveCSS("outline-style", "solid");
    await expect(control).toHaveCSS("outline-width", "2px");
    await expect(control).not.toHaveCSS("outline-color", "rgba(0, 0, 0, 0)");
}

test("state-opened pipeline and company dialogs restore keyboard focus @mobile", async ({ page, isMobile }) => {
    for (const item of [
        { path: "/records/pipelines", opener: "Add pipeline", title: "New pipeline" },
        { path: "/records/companies", opener: "Add company", title: "New company" },
    ]) {
        await page.goto(item.path);
        const opener = page.getByRole("main").getByRole("button", { name: item.opener, exact: true }).first();
        await opener.focus();
        await opener.press("Enter");
        const dialog = page.getByRole("dialog", { name: item.title, exact: true });
        await expect(dialog).toBeVisible();
        if (isMobile) await expectHitArea(dialog.getByRole("button", { name: "Close", exact: true }), 32);
        await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
        await expect(dialog).not.toBeVisible();
        await expect(opener).toBeFocused();
    }
});

test("sidebar menu triggers open with Enter and Space with motion enabled", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.goto("/dashboard");
    for (const name of ["Switch workspace", /Open notifications/]) {
        const trigger = page.locator('aside[aria-label="Primary sidebar"]').filter({ visible: true })
            .getByRole("button", { name, includeHidden: true });
        for (const key of ["Enter", "Space"]) {
            await trigger.focus();
            await trigger.press(key);
            await expect(trigger).toHaveAttribute("aria-expanded", "true");
            await expect(page.getByRole("menu")).toBeVisible();
            await page.keyboard.press("Escape");
            await expect(trigger).toHaveAttribute("aria-expanded", "false");
        }
    }
});

test("editing fields use 16px at 390px and 14px at md @mobile", async ({ page }, testInfo) => {
    for (const width of [390, 768]) {
        await page.setViewportSize({ width, height: 900 });
        const expected = width < 768 ? "16px" : "14px";
        await page.goto("/records/contacts");
        await expect(page.getByRole("textbox", { name: "Search", exact: true })).toHaveCSS("font-size", expected);
        const contact = runFixture(testInfo.project.name).contacts.activity;
        await page.goto(`/records/contacts/${contact.id}`);
        await page.getByRole("button", { name: "Log activity" }).first().click();
        const dialog = page.getByRole("dialog", { name: "Log activity", exact: true });
        await expect(dialog.getByLabel("Subject", { exact: true })).toHaveCSS("font-size", expected);
        await expect(dialog.locator('[contenteditable="true"]')).toHaveCSS("font-size", expected);
        await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
        await expect(page.locator('meta[name="viewport"]')).not.toHaveAttribute("content", /maximum-scale/);
    }
});

test("forced colors preserve all primitive outlines and normal styles in both themes @mobile", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const fixture = runFixture(testInfo.project.name);
    const viewport = page.viewportSize();
    for (const theme of ["light", "dark"]) {
        await page.goto("/records/pipelines");
        await page.evaluate((value) => {
            localStorage.setItem("theme", value);
            document.documentElement.classList.toggle("dark", value === "dark");
        }, theme);
        const create = page.getByRole("button", { name: "Add pipeline", exact: true });
        const segment = page.getByRole("group", { name: "Display mode" }).getByRole("button").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalStylesUnchanged(create);
        await expectNormalStylesUnchanged(segment);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(create);
        await expectForcedFocus(segment);
        await expect(create).toHaveCSS("border-top-width", "1px");
        await expect(create).toHaveCSS("border-top-style", "solid");
        await page.goto("/settings/personal/profile");
        const input = page.locator('[data-slot="input"]').first();
        const select = page.locator('[data-slot="select-trigger"]').first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalStylesUnchanged(input);
        await expectNormalStylesUnchanged(select);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(input);
        await expectForcedFocus(select);
        await page.goto("/settings/personal/notifications");
        const toggle = page.getByRole("switch").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalStylesUnchanged(toggle);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(toggle);
        await page.goto("/settings/workspace/people#members");
        const tab = page.getByRole("tab").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalStylesUnchanged(tab);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(tab);
        await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(fixture.contacts.peek.name)}`);
        const checkbox = page.getByRole("checkbox").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalStylesUnchanged(checkbox);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(checkbox);
        await page.setViewportSize({ width: 1024, height: 900 });
        await page.goto(`/records/deals?view=grid&q=${encodeURIComponent(fixture.deals.primary.name)}`);
        const card = page.getByRole("link").filter({ hasText: fixture.deals.primary.name }).first();
        await expect(card).toHaveCSS("border-top-width", "1px");
        await expect(card).toHaveCSS("border-top-style", "solid");
        if (viewport) await page.setViewportSize(viewport);
        await page.emulateMedia({ forcedColors: "none" });
    }
});

test("a near miss inside the task checkbox cell completes the task @mobile-only", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const description = `Touch target ${randomUUID()}`;
    const taskId = await createTask(page, fixture.workspaceId, fixture.contacts.activity.id, description);
    try {
        await page.goto(`/activity/tasks?q=${encodeURIComponent(description)}`);
        const row = page.getByRole("listitem").filter({ hasText: description });
        const checkbox = row.getByRole("checkbox");
        await expect(checkbox).toBeEnabled();
        await checkbox.scrollIntoViewIfNeeded();
        await expectHitArea(checkbox, 18);
        const target = await checkbox.evaluate((element) => {
            const box = element.getBoundingClientRect();
            const cell = element.parentElement?.getBoundingClientRect();
            const x = box.left - 6;
            const y = box.top + box.height / 2;
            return {
                x, y, cellWidth: cell?.width ?? 0, cellHeight: cell?.height ?? 0,
                ownsPoint: document.elementFromPoint(x, y)?.closest('[data-slot="checkbox"]') === element,
            };
        });
        expect(target.cellWidth).toBeGreaterThanOrEqual(44);
        expect(target.cellHeight).toBeGreaterThanOrEqual(44);
        expect(target.ownsPoint).toBe(true);
        const [completed] = await Promise.all([
            page.waitForResponse((result) => new URL(result.url()).pathname === `/api/tasks/${taskId}` && result.request().method() === "PUT"),
            page.touchscreen.tap(target.x, target.y),
        ]);
        expect(completed.ok()).toBe(true);
        expect(await completed.json()).toMatchObject({ completed: true });
        await expect(page.getByRole("dialog")).toHaveCount(0);
    } finally {
        await deleteTask(page, fixture.workspaceId, taskId);
    }
});

test("inline tag actions keep their visual size and a 44px hit area @mobile-only", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const name = `Touch tag ${randomUUID()}`;
    await seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request)).post("/api/tags", { name, color: "#abcdef" });
    await page.goto(`/library/tags?q=${encodeURIComponent(name)}`);
    await expectHitArea(page.getByRole("button", { name: `Actions for tag ${name}` }), 24);
});

test("dense stage and peek controls own taps at their visible edges @mobile-only", async ({ page }, testInfo) => {
    await page.goto("/records/pipelines");
    await page.getByRole("button", { name: "Add pipeline", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "New pipeline", exact: true });
    for (const [index, name] of ["First", "Middle", "Last"].entries()) {
        await dialog.getByRole("button", { name: "Add stage", exact: true }).click();
        await dialog.locator(`#new-pipeline-stage-${index}`).fill(name);
    }
    const up = dialog.locator("#new-pipeline-stage-1-up");
    const down = dialog.locator("#new-pipeline-stage-1-down");
    await expectVisibleEdgesBelongTo(up);
    await expectVisibleEdgesBelongTo(down);
    await up.scrollIntoViewIfNeeded();
    const point = await up.evaluate((element) => {
        const box = element.getBoundingClientRect();
        return { x: box.left + box.width / 2, y: box.bottom - 2 };
    });
    await page.touchscreen.tap(point.x, point.y);
    await expect(dialog.locator("#new-pipeline-stage-0")).toHaveValue("Middle");
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    const fixture = runFixture(testInfo.project.name);
    const query = fixture.contacts.peek.name.replace(/^Peek /, "");
    await page.goto(`/records/contacts?view=table&sort=name&dir=asc&size=25&q=${encodeURIComponent(query)}&peek=person%3A${fixture.contacts.peek.id}`);
    const peek = page.locator("[data-record-peek]");
    await expect(peek).toBeVisible();
    for (const name of ["Previous record", "Next record", "Close"]) {
        const control = peek.getByRole("button", { name, exact: true });
        await expect(control).toBeEnabled();
        await expectVisibleEdgesBelongTo(control);
    }
});

test("task sheet callers retain mobile editor fonts after class merging @mobile", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const taskId = await createTask(page, fixture.workspaceId, fixture.contacts.activity.id, `Editor font ${randomUUID()}`);
    try {
        for (const width of [390, 768]) {
            await page.setViewportSize({ width, height: 900 });
            await page.goto(`/activity/tasks?task=${taskId}`);
            const editor = page.locator('#task-description[contenteditable="true"]');
            await expect(editor).toBeFocused();
            await expect(editor).toHaveCSS("font-size", width < 768 ? "16px" : "14px");
            await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
            await expect(editor).toBeHidden();
        }
    } finally {
        await deleteTask(page, fixture.workspaceId, taskId);
    }
});
