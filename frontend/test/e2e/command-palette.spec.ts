import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

for (const [locale, theme] of [["en", "light"], ["ja", "dark"]] as const) {
    test(`palette query replacement, context and shortcuts (${locale}/${theme})`, async ({ page, context }, testInfo) => {
        await useLocale(context, locale);
        await context.addInitScript((value) => localStorage.setItem("theme", value), theme);
        await page.route(/\/api\/workflows\?archived=false$/, (route) => route.fulfill({ json: [] }));
        await page.goto("/dashboard");
        const inline = page.getByRole("combobox");
        await inline.fill("log");
        await page.keyboard.press("ControlOrMeta+k");
        const palette = page.getByRole("dialog", { name: message(locale, "actions", "Actions.palette.trigger") });
        const input = palette.getByRole("combobox");
        const expectQuerySelected = () => expect.poll(() => input.evaluate((element) => {
            if (!(element instanceof HTMLInputElement)) throw new Error("Expected palette input");
            return [element.selectionStart, element.selectionEnd];
        })).toEqual([0, 3]);
        await expect(input).toHaveValue("log");
        await expectQuerySelected();
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();
        await page.keyboard.press("ControlOrMeta+k");
        await expect(input).toHaveValue("log");
        await expectQuerySelected();
        await input.pressSequentially("task");
        await expect(input).toHaveValue("task");
        await input.fill("");
        await expect(palette.getByText(message(locale, "actions", "Actions.group.record"), { exact: true })).toHaveCount(0);
        await expect(palette.getByRole("option", { name: message(locale, "actions", "Actions.workspace.runWorkflow") })).toHaveCount(0);

        const activity = palette.getByRole("option", { name: new RegExp(message(locale, "actions", "Actions.create.activity")) });
        await expect(activity.locator('[data-slot="command-shortcut"]')).toContainText(/Ctrl\+Alt\+A|⌘⌥A/);
        await page.keyboard.press("ArrowDown");
        const selected = palette.locator('[data-slot="command-item"][aria-selected="true"]');
        await expect(selected).toHaveCount(1);
        const selectedStyle = await selected.evaluate((element) => {
            const style = getComputedStyle(element);
            return { background: style.backgroundColor, shadow: style.boxShadow };
        });
        expect(selectedStyle.background).not.toBe("rgba(0, 0, 0, 0)");
        expect(selectedStyle.shadow).toContain("inset");
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();

        await page.goto("/search?query=log");
        await page.keyboard.press("ControlOrMeta+k");
        await input.fill("");
        await expect(palette.getByText(message(locale, "actions", "Actions.group.record"), { exact: true })).toHaveCount(0);
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();

        const contact = runFixture(testInfo.project.name).contacts.activity;
        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
        await page.keyboard.press("ControlOrMeta+k");
        await input.fill("");
        await expect(palette.getByRole("option", { name: new RegExp(message(locale, "actions", "Actions.record.runWorkflow")) })).toBeVisible();
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();

        for (const [key, label] of [["t", "task"], ["n", "note"], ["a", "activity"]] as const) {
            await page.getByRole("heading", { name: contact.name }).first().click();
            await page.keyboard.press(`ControlOrMeta+Alt+${key}`);
            const composer = page.getByRole("dialog");
            await expect(composer.getByRole("heading", { name: message(locale, "actions", `Actions.create.${label}`) }).first()).toBeVisible();
            await page.keyboard.press("Escape");
            await expect(composer).toBeHidden();
        }
    });

    test(`context-free workflow launcher belongs to Workspace (${locale})`, async ({ page, context }) => {
        await useLocale(context, locale);
        await page.route(/\/api\/workflows\?archived=false$/, (route) => route.fulfill({ json: [{
            id: 1, name: "Follow up", enabled: true, runtimeOwner: "canonical", executionAvailable: true,
            recordType: "person", activeVersion: { id: 1, number: 1, publishedAt: "2026-11-01T00:00:00Z" },
        }] }));
        await page.goto("/dashboard");
        await page.keyboard.press("ControlOrMeta+k");
        const palette = page.getByRole("dialog", { name: message(locale, "actions", "Actions.palette.trigger") });
        const group = palette.locator("[cmdk-group]").filter({
            has: page.locator("[cmdk-group-heading]", { hasText: message(locale, "actions", "Actions.group.workspace") }),
        });
        const launcher = group.getByRole("option", { name: message(locale, "actions", "Actions.workspace.runWorkflow") });
        await expect(launcher).toBeVisible();
        await expect(palette.getByText(message(locale, "actions", "Actions.group.record"), { exact: true })).toHaveCount(0);
        await launcher.click();
        await expect(page.getByRole("dialog", { name: message(locale, "workflow-operations", "WorkflowOperations.manual.title") })).toBeVisible();
    });
}
