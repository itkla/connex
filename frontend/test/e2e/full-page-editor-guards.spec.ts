import { expect, test } from '@playwright/test';
import { useLocale } from './support/locale';
import { message } from './support/messages';

const editors = [
    { key: 'report', path: '/insights/reports/new', destination: '/insights/reports', namespace: 'reports', name: 'Reports.builder.name', back: 'Reports.common.cancel' },
    { key: 'template', path: '/library/documents/new', destination: '/library/documents', namespace: 'document-templates', name: 'DocumentTemplateBuilder.name', back: 'DocumentTemplateBuilder.back' },
    { key: 'workflow', path: '/workflows/new', destination: '/workflows', namespace: 'workspace', name: 'WorkspaceWorkflows.nameLabel', back: 'WorkspaceWorkflows.backToList' },
] as const;

for (const locale of ['en', 'ja'] as const) {
    for (const editor of editors) {
        test(`${editor.key} keeps dirty content and confirms its return destination in ${locale} @mobile`, async ({ page }) => {
            await useLocale(page, locale);
            await page.goto(editor.path);
            const input = page.getByRole('textbox', { name: message(locale, editor.namespace, editor.name), exact: true });
            const back = page.getByRole('button', { name: message(locale, editor.namespace, editor.back), exact: true });
            const dialog = page.getByRole('dialog', { name: message(locale, 'common', 'ConfirmDiscard.title') });
            const draft = `Unsaved ${editor.key}`;
            await input.fill(draft);
            await back.click();
            await expect(dialog).toBeVisible();
            await dialog.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.keepEditing') }).click();
            await expect(input).toHaveValue(draft);
            await expect(back).toBeFocused();
            await expect(page).toHaveURL(editor.path);

            const warning = page.waitForEvent('dialog');
            const reload = page.evaluate(() => window.location.reload());
            const browserDialog = await warning;
            expect(browserDialog.type()).toBe('beforeunload');
            await browserDialog.dismiss();
            await reload;
            await expect(input).toHaveValue(draft);

            const closeWarning = page.waitForEvent('dialog');
            await page.close({ runBeforeUnload: true });
            const closeDialog = await closeWarning;
            expect(closeDialog.type()).toBe('beforeunload');
            await closeDialog.dismiss();
            expect(page.isClosed()).toBe(false);
            await expect(input).toHaveValue(draft);

            await back.click();
            await dialog.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.discard') }).click();
            await expect(page).toHaveURL(editor.destination);
        });
    }
}

test('report Cancel from a direct link returns to reports without browser history', async ({ page }) => {
    await page.goto('/insights/reports/new');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await expect(page.getByRole('dialog')).toHaveCount(0);
});

for (const navigationApi of [true, false]) {
test(`sidebar and browser Back/Forward retain a report until discard is confirmed (Navigation API: ${navigationApi})`, async ({ page }) => {
    if (!navigationApi) {
        await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    }
    await page.goto('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    const input = page.getByRole('textbox', { name: 'Name', exact: true });
    const dialog = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await input.fill('Report kept after Back');
    await page.evaluate(() => window.history.back());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Report kept after Back');
    await expect(input).toBeFocused();
    await expect(page).toHaveURL('/insights/reports/new');
    await page.evaluate(() => window.history.back());
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await page.goForward();
    await expect(input).toHaveValue('');

    await input.fill('Sidebar draft');
    const deals = page.locator('a[href="/records/deals"]:visible').first();
    await deals.click();
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Sidebar draft');
    await expect(deals).toBeFocused();
    await deals.click();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
    await page.goBack();
    await expect(input).toHaveValue('');
    await input.fill('Report kept after Forward');
    await page.evaluate(() => window.history.forward());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Report kept after Forward');
    await expect(input).toBeFocused();
    await page.evaluate(() => window.history.forward());
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
});
}
