import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { useLocale } from './support/locale';
import { message } from './support/messages';

const editors = [
    { key: 'report', path: '/insights/reports/new', destination: '/insights/reports', namespace: 'reports', name: 'Reports.builder.name', back: 'Reports.common.cancel' },
    { key: 'template', path: '/library/documents/new', destination: '/library/documents', namespace: 'document-templates', name: 'DocumentTemplateBuilder.name', back: 'DocumentTemplateBuilder.back' },
    { key: 'workflow', path: '/workflows/new', destination: '/workflows', namespace: 'workspace', name: 'WorkspaceWorkflows.nameLabel', back: 'WorkspaceWorkflows.backToList' },
] as const;

for (const locale of ['en', 'ja'] as const) {
    test(`an untouched template returns to Documents without a discard prompt in ${locale} @mobile`, async ({ page }) => {
        await useLocale(page, locale);
        await page.goto('/library/documents/new');
        await expect(page.locator('.tiptap')).toBeVisible();
        await page.getByRole('button', { name: message(locale, 'document-templates', 'DocumentTemplateBuilder.back'), exact: true }).click();
        await expect(page).toHaveURL('/library/documents');
        await expect(page.getByRole('dialog')).toHaveCount(0);
    });
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

test('an unindexed legacy history jump keeps the destination URL and router synchronized', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    await page.goto('/docs');
    await page.locator('aside a[href^="/docs/"]:visible').first().click();
    await expect(page).toHaveURL(/\/docs\/[^/]+\/[^/?]+/);
    const articleUrl = page.url();
    await page.getByRole('link', { name: 'Open app', exact: true }).click();
    await expect(page).toHaveURL('/dashboard');
    await page.locator('a[href="/insights/reports"]:visible').first().click();
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Legacy history jump');
    await page.evaluate(() => window.history.go(-3));
    await expect(page).toHaveURL(articleUrl);
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
});

test('a native skip-link entry followed by go(-2) keeps the list URL and rendered route synchronized', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    await page.goto('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Skip-link history draft');
    const skip = page.locator('a[href="#app-main"]');
    await skip.focus();
    await skip.press('Enter');
    await expect(page).toHaveURL('/insights/reports/new#app-main');
    await page.evaluate(() => window.history.go(-2));
    await expect(page).toHaveURL('/insights/reports');
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
});

test('searching for the current workflow leaves its draft and departure protection intact', async ({ page }) => {
    const savedName = `Search guard ${randomUUID()}`;
    await page.goto('/workflows/new');
    const name = page.getByRole('textbox', { name: 'Workflow name', exact: true });
    await name.fill(savedName);
    await page.getByRole('button', { name: 'Save draft', exact: true }).click();
    await expect(page).toHaveURL(/\/workflows\/\d+$/);
    await expect(name).toHaveValue(savedName);
    await name.fill(`${savedName} edited`);
    await page.getByPlaceholder('Search for anything').fill(savedName);
    await page.getByRole('option', { name: new RegExp(savedName) }).first().click();
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
    await expect(name).toHaveValue(`${savedName} edited`);

    const warning = page.waitForEvent('dialog');
    const reload = page.evaluate(() => window.location.reload());
    const nativeWarning = await warning;
    expect(nativeWarning.type()).toBe('beforeunload');
    await nativeWarning.dismiss();
    await reload;
    await expect(name).toHaveValue(`${savedName} edited`);
    await page.locator('a[href="/records/deals"]:visible').first().click();
    const discard = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(discard).toBeVisible();
    await discard.getByRole('button', { name: 'Keep editing' }).click();
    await expect(name).toHaveValue(`${savedName} edited`);
});

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
