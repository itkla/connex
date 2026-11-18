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
        const unselected = palette.locator('[data-slot="command-item"][data-selected="false"]').first();
        expect(await unselected.evaluate((element) => getComputedStyle(element).boxShadow)).toBe("none");
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
        const published = {
            id: 1, name: "Follow up", enabled: true, runtimeOwner: "canonical", executionAvailable: true,
            recordType: "person", activeVersion: { id: 1, number: 1, publishedAt: "2026-11-01T00:00:00Z" },
        };
        let workflows = [{ ...published, enabled: false }];
        let reads = 0;
        await page.route(/\/api\/workflows\?archived=false$/, (route) => {
            reads += 1;
            return route.fulfill({ json: workflows });
        });
        await page.goto("/dashboard");
        await expect.poll(() => reads).toBeGreaterThan(0);
        workflows = [published];
        await page.keyboard.press("ControlOrMeta+k");
        const palette = page.getByRole("dialog", { name: message(locale, "actions", "Actions.palette.trigger") });
        const group = palette.locator("[cmdk-group]").filter({
            has: page.locator("[cmdk-group-heading]", { hasText: message(locale, "actions", "Actions.group.workspace") }),
        });
        const launcher = group.getByRole("option", { name: message(locale, "actions", "Actions.workspace.runWorkflow") });
        await expect(launcher).toBeVisible();
        await expect(palette.getByText(message(locale, "actions", "Actions.group.record"), { exact: true })).toHaveCount(0);
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();
        workflows = [];
        const beforeArchiveRead = reads;
        await page.keyboard.press("ControlOrMeta+k");
        await expect.poll(() => reads).toBeGreaterThan(beforeArchiveRead);
        await expect(launcher).toHaveCount(0);
        await page.keyboard.press("Escape");
        await expect(palette).toBeHidden();
        workflows = [published];
        await page.keyboard.press("ControlOrMeta+k");
        await expect(launcher).toBeVisible();
        await launcher.click();
        await expect(page.getByRole("dialog", { name: message(locale, "workflow-operations", "WorkflowOperations.manual.title") })).toBeVisible();
    });

    test(`create shortcuts preserve an unsaved company composer (${locale})`, async ({ page, context }) => {
        await useLocale(context, locale);
        await page.goto("/dashboard");
        await page.keyboard.press("ControlOrMeta+k");
        await page.getByRole("option", { name: message(locale, "actions", "Actions.create.company") }).click();
        const composer = page.getByRole("dialog", { name: message(locale, "companies", "CompaniesNewDialog.title") });
        const name = composer.locator("#company-name");
        await name.fill("Unsaved company draft");
        const cancel = composer.getByRole("button", { name: message(locale, "companies", "CompaniesNewDialog.cancel"), exact: true });
        await cancel.focus();
        await page.keyboard.press("ControlOrMeta+Alt+t");
        await expect(composer).toBeVisible();
        await expect(name).toHaveValue("Unsaved company draft");
        await expect(page.getByRole("dialog")).toHaveCount(1);
        await cancel.click();
        await expect(page.getByRole("dialog", { name: message(locale, "common", "ConfirmDiscard.title") })).toBeVisible();
    });
}
