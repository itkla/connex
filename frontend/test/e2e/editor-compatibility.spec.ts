import { expect, test } from "@playwright/test";

test("note editor preserves typed formatting through autosave and reload", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    const marker = `EditorNote${Date.now().toString(36)}`;

    await page.goto("/activity/notes/new");
    const editor = page.locator('.tiptap[contenteditable="true"]');
    await expect(editor).toBeVisible();
    await editor.fill(marker);
    await editor.press("ControlOrMeta+a");
    await editor.press("ControlOrMeta+b");
    await expect(editor.locator("strong")).toHaveText(marker);
    await expect(page).toHaveURL(/\/activity\/notes\/\d+/, { timeout: 20_000 });
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();

    await page.reload();
    await expect(editor.locator("strong")).toHaveText(marker);
    expect(errors).toEqual([]);
});

test("document editor preserves formatting and opens custom slash suggestions", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
    });
    const marker = `EditorDocument${Date.now().toString(36)}`;

    await page.goto("/library/documents/new");
    const editor = page.locator('.tiptap[contenteditable="true"]');
    await expect(editor).toBeVisible();
    await editor.fill(marker);
    await editor.press("ControlOrMeta+a");
    await editor.press("ControlOrMeta+b");
    await expect(editor.locator("strong")).toHaveText(marker);
    await editor.press("ArrowRight");
    await editor.press("Enter");
    await editor.pressSequentially("/");
    await expect(page.getByRole("listbox")).toBeVisible();
    await editor.press("Escape");

    await page.locator("#tpl-name").fill(marker);
    const savedResponse = page.waitForResponse((response) =>
        response.url().endsWith("/api/document-templates") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Save", exact: true }).click();
    const response = await savedResponse;
    expect(response.ok()).toBe(true);
    const saved: unknown = await response.json();
    if (typeof saved !== "object" || saved === null || !("id" in saved) || typeof saved.id !== "number") {
        throw new Error("Expected a saved document template id");
    }

    await page.goto(`/library/documents/${saved.id}`);
    await expect(editor.locator("strong").first()).toHaveText(marker);
    expect(errors).toEqual([]);
});
