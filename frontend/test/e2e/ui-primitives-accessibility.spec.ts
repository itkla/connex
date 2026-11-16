import { randomUUID } from "node:crypto";
import { expect, test, type Locator } from "@playwright/test";
import { csrfBootstrap, seeder } from "./support/api";
import { runFixture } from "./support/fixtures";

async function expectNormalPixelsUnchanged(control: Locator) {
    await expect(control).toBeVisible();
    const before = await control.screenshot({ animations: "disabled" });
    const original = await control.getAttribute("class");
    await control.evaluate((element) => {
        element.setAttribute("class", (element.getAttribute("class") ?? "").split(" ")
            .filter((name) => !name.startsWith("forced-colors:"))
            .map((name) => name === "outline-hidden" ? "outline-none" : name).join(" "));
    });
    try {
        const after = await control.screenshot({ animations: "disabled" });
        expect(after.equals(before)).toBe(true);
    } finally {
        await control.evaluate((element, className) => { element.setAttribute("class", className ?? ""); }, original);
    }
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
        { path: "/records/companies", opener: "New", title: "New company" },
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
        const trigger = page.getByRole("complementary", { name: "Primary sidebar" }).getByRole("button", { name });
        for (const key of ["Enter", "Space"]) {
            await trigger.focus();
            await trigger.press(key);
            await expect(trigger).toHaveAttribute("aria-expanded", "true");
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

test("forced colors preserve all primitive outlines and normal pixels in both themes @mobile", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    const fixture = runFixture(testInfo.project.name);
    for (const theme of ["light", "dark"]) {
        await page.goto("/records/pipelines");
        await page.evaluate((value) => {
            localStorage.setItem("theme", value);
            document.documentElement.classList.toggle("dark", value === "dark");
        }, theme);
        const create = page.getByRole("button", { name: "Add pipeline", exact: true });
        const segment = page.getByRole("group", { name: "Display mode" }).getByRole("button").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalPixelsUnchanged(create);
        await expectNormalPixelsUnchanged(segment);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(create);
        await expectForcedFocus(segment);
        await expect(create).toHaveCSS("border-top-width", "1px");
        await expect(create).toHaveCSS("border-top-style", "solid");
        await page.goto("/settings/personal/profile");
        const input = page.locator('[data-slot="input"]').first();
        const select = page.locator('[data-slot="select-trigger"]').first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalPixelsUnchanged(input);
        await expectNormalPixelsUnchanged(select);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(input);
        await expectForcedFocus(select);
        await page.goto("/settings/personal/notifications");
        const toggle = page.getByRole("switch").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalPixelsUnchanged(toggle);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(toggle);
        await page.goto("/settings/workspace/people#members");
        const tab = page.getByRole("tab").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalPixelsUnchanged(tab);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(tab);
        await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(fixture.contacts.peek.name)}`);
        const checkbox = page.getByRole("checkbox").first();
        await page.emulateMedia({ forcedColors: "none" });
        await expectNormalPixelsUnchanged(checkbox);
        await page.emulateMedia({ forcedColors: "active" });
        await expectForcedFocus(checkbox);
        await page.goto(`/records/deals?view=grid&q=${encodeURIComponent(fixture.deals.primary.name)}`);
        const card = page.getByRole("link").filter({ hasText: fixture.deals.primary.name }).first();
        await expect(card).toHaveCSS("border-top-width", "1px");
        await expect(card).toHaveCSS("border-top-style", "solid");
        await page.emulateMedia({ forcedColors: "none" });
    }
});

test("a near miss on the 18px task checkbox completes it without opening the row @mobile-only", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const response = await page.request.get("/api/auth/me");
    expect(response.ok()).toBe(true);
    const user: unknown = await response.json();
    if (typeof user !== "object" || user === null || !("id" in user) || typeof user.id !== "number") {
        throw new Error("Missing current member id");
    }
    const description = `Touch target ${randomUUID()}`;
    const created = await seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request)).post("/api/tasks", {
        description, completed: false, assignedToId: user.id, personId: fixture.contacts.activity.id,
    });
    if (typeof created.id !== "number") throw new Error("Missing task id");
    await page.goto(`/activity/tasks?q=${encodeURIComponent(description)}`);
    const row = page.getByRole("listitem").filter({ hasText: description });
    const checkbox = row.getByRole("checkbox");
    await expect(checkbox).toHaveCSS("width", "18px");
    const target = await checkbox.evaluate((element) => {
        const hit = getComputedStyle(element, "::after");
        const box = element.getBoundingClientRect();
        return { width: parseFloat(hit.width), height: parseFloat(hit.height), x: box.x - 6, y: box.y + box.height / 2 };
    });
    expect(target.width).toBeGreaterThanOrEqual(44);
    expect(target.height).toBeGreaterThanOrEqual(44);
    const completed = page.waitForResponse((result) => result.url().endsWith(`/api/tasks/${created.id}`) && result.request().method() === "PUT");
    await page.touchscreen.tap(target.x, target.y);
    expect((await completed).ok()).toBe(true);
    await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("inline tag actions keep their visual size and a 44px hit area @mobile-only", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const name = `Touch tag ${randomUUID()}`;
    await seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request)).post("/api/tags", { name, color: "#abcdef" });
    await page.goto(`/library/tags?q=${encodeURIComponent(name)}`);
    await expectHitArea(page.getByRole("button", { name: `Actions for tag ${name}` }), 24);
});
